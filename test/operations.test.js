import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { clientSettings, serverAddress } from "../scripts/client-settings.js";
import { checkInboxStatus } from "../scripts/inbox-status.js";
import { startupDefinition } from "../scripts/startup-definition.js";
import { serveApk } from "../scripts/serve-apk.js";
import { writePrivate, windowsPrivacyCommands } from "../scripts/private-file.js";

async function temporary(t) {
  const directory = await mkdtemp(join(tmpdir(), "phone-inbox-ops-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

test("private writes refuse accidental rotation and replace files without broad permissions", async t => {
  const directory = await temporary(t);
  const path = join(directory, "private.env");
  await writePrivate(path, "original");
  await assert.rejects(writePrivate(path, "unwanted rotation"), { code: "EEXIST" });
  assert.equal(await readFile(path, "utf8"), "original");
  await writePrivate(path, "approved replacement", { replace: true });
  assert.equal(await readFile(path, "utf8"), "approved replacement");
  if (process.platform !== "win32") assert.equal((await stat(path)).mode & 0o777, 0o600);
  await assert.rejects(stat(path + ".tmp"), { code: "ENOENT" });
  const commands = windowsPrivacyCommands("C:\\Users\\Example's\\private.env", "S-1-5-21-123-1001");
  assert.deepEqual(commands[0], ["icacls", ["C:\\Users\\Example's\\private.env", "/inheritance:r", "/grant:r", "*S-1-5-21-123-1001:(F)"]]);
  const verify = Buffer.from(commands[1][1].at(-1), "base64").toString("utf16le");
  assert.match(verify, /Get-Acl -LiteralPath 'C:\\Users\\Example''s\\private.env'/);
  assert.match(verify, /AreAccessRulesProtected/);
  assert.match(verify, /IdentityReference.Value -ne 'S-1-5-21-123-1001'/);
});

test("client configuration selects only bot credentials and uses explicit endpoint and hosting precedence", async t => {
  const directory = await temporary(t);
  await writeFile(join(directory, ".dev.vars"), 'PHONE_TOKEN="fake-phone"\nBOT_TOKEN="fake-bot"\n', { mode: 0o600 });
  await writeFile(join(directory, ".local.env"), 'BIND_ADDRESS="100.64.0.1"\nPORT="8787"\nHOSTING_MODE="pc"\n');
  const settings = await clientSettings({ directory, environment: {} });
  assert.deepEqual(settings, { endpoint: "http://100.64.0.1:8787", hostingMode: "pc", headers: { Authorization: "Bearer fake-bot" } });
  const overridden = await clientSettings({ directory, endpoint: "https://inbox.example.com", hosting: "cloud", environment: { INBOX_URL: "https://ignored.example.com" } });
  assert.equal(overridden.endpoint, "https://inbox.example.com");
  assert.equal(overridden.hostingMode, "cloud");
  await writeFile(join(directory, ".env.bot"), 'BOT_TOKEN="fake-cloud"\nACCESS_CLIENT_ID="fake-id"\nACCESS_CLIENT_SECRET="fake-secret"\n', { mode: 0o600 });
  const cloud = await clientSettings({ directory, credentials: ".env.bot", endpoint: "https://inbox.example.com", hosting: "cloud", environment: {} });
  assert.equal(cloud.headers["CF-Access-Client-Secret"], "fake-secret");
  await assert.rejects(clientSettings({ directory, credentials: ".env.bot", environment: {} }), /non-tailnet HTTPS/);
});

test("client address validation does not send credentials to public HTTP or embedded paths", () => {
  for (const endpoint of ["http://public.example.com", "http://100.128.0.1", "https://user:secret@example.com", "https://example.com/path", "https://example.com?secret=x", "https://example.com\\path"]) {
    assert.throws(() => serverAddress({ endpoint, environment: {} }));
  }
  assert.equal(serverAddress({ local: { INBOX_URL: "https://inbox.example.com/" }, environment: {} }), "https://inbox.example.com");
  assert.equal(serverAddress({ environment: {} }), "http://127.0.0.1:8787");
});

test("status distinguishes expected PC downtime, wrong credentials and usable phone states without raw errors", async () => {
  const settings = { endpoint: "https://inbox.example.com", headers: { Authorization: "Bearer fake-bot" }, hostingMode: "pc" };
  const offline = await checkInboxStatus(settings, async () => { throw new Error("secret transport detail"); });
  assert.deepEqual(offline, { state: "relay_unreachable", lastSeenAt: null, nextAction: "Wait until the PC is on and signed in, then check again." });
  const denied = await checkInboxStatus(settings, async () => new Response("secret auth detail", { status: 403 }));
  assert.equal(denied.state, "credentials_rejected");
  assert.doesNotMatch(JSON.stringify(denied), /secret auth detail/);
  const expected = { state: "notification_access_revoked", lastSeenAt: "2026-10-13T12:00:00Z", nextAction: "Ask the user to allow notification access in Phone inbox." };
  const result = await checkInboxStatus(settings, async (url, options) => {
    assert.equal(url, "https://inbox.example.com/health");
    assert.equal(options.headers.Authorization, "Bearer fake-bot");
    assert.equal(options.redirect, "error");
    return Response.json({ ...expected, role: "bot", sequence: 55 });
  });
  assert.deepEqual(result, expected);
  assert.equal((await checkInboxStatus(settings, async () => Response.json({ ...expected, role: "phone" }))).state, "credentials_rejected");
});

test("status rejects login pages, oversized bodies and malformed status without exposing them", async () => {
  const settings = { endpoint: "https://inbox.example.com", headers: {}, hostingMode: "always-on" };
  for (const body of ["<html>secret login page</html>", "x".repeat(65_537), JSON.stringify({ role: "bot", state: "unknown", lastSeenAt: null, nextAction: "secret details" })]) {
    const result = await checkInboxStatus(settings, async () => new Response(body));
    assert.equal(result.state, "relay_unreachable");
    assert.doesNotMatch(JSON.stringify(result), /secret|xxxx/);
    assert.match(result.nextAction, /host/);
  }
});

test("startup definitions preserve paths and least-privilege login execution on each supported platform", async () => {
  const linuxTemplate = await readFile(new URL("../ops/phone-digest.service", import.meta.url), "utf8");
  const common = { root: "/home/example/Phone inbox 100%", node: "/opt/Node runtime/node", home: "/home/example" };
  const linux = startupDefinition({ ...common, platform: "linux", linuxTemplate }).content;
  assert.match(linux, /WorkingDirectory=\/home\/example\/Phone inbox 100%%/);
  assert.match(linux, /ExecStart="\/opt\/Node runtime\/node"/);
  assert.match(linux, /WantedBy=default.target/);
  const mac = startupDefinition({ ...common, root: "/Users/example/A&B", platform: "darwin" }).content;
  assert.match(mac, /<string>\/Users\/example\/A&amp;B\/scripts\/dev.js<\/string>/);
  assert.match(mac, /<key>RunAtLoad<\/key><true\/>/);
  const windowsRoot = "C:\\Users\\Example\\Phone & inbox's";
  const windows = startupDefinition({ root: windowsRoot, node: "C:\\Program Files\\nodejs\\node.exe", home: "C:\\Users\\Example", userId: "S-1-5-21-123-1001", platform: "win32" }).content;
  assert.match(windows, /<LogonType>InteractiveToken<\/LogonType><RunLevel>LeastPrivilege<\/RunLevel>/);
  assert.match(windows, /<ExecutionTimeLimit>PT0S<\/ExecutionTimeLimit>/);
  const encoded = windows.match(/-EncodedCommand ([A-Za-z0-9+/=]+)/)[1];
  assert.equal(Buffer.from(encoded, "base64").toString("utf16le"), "& 'C:\\Program Files\\nodejs\\node.exe' 'C:\\Users\\Example\\Phone & inbox''s\\scripts\\dev.js'; exit $LASTEXITCODE");
  assert.throws(() => startupDefinition({ ...common, platform: "linux", linuxTemplate, root: "/tmp/path\nExecStart=bad" }), /Unsupported/);
  assert.throws(() => startupDefinition({ ...common, platform: "win32", userId: "untrusted-user" }), /Windows user/);
});

test("APK download serves only the selected artifact, supports repeat requests and rejects unsafe binds", async t => {
  const directory = await temporary(t);
  const apk = join(directory, "test.apk");
  const content = Buffer.from("fake APK bytes for transport testing");
  await writeFile(apk, content);
  await writeFile(join(directory, ".dev.vars"), "SECRET=must-not-serve");
  await assert.rejects(serveApk({ apk, host: "0.0.0.0", port: 0 }), /Tailscale/);
  await assert.rejects(serveApk({ apk: join(directory, ".dev.vars"), host: "127.0.0.1", port: 0 }), /APK file/);
  const running = await serveApk({ apk, host: "127.0.0.1", port: 0 });
  t.after(() => running.close());
  const base = `http://127.0.0.1:${running.server.address().port}`;
  assert.equal(running.sha256, createHash("sha256").update(content).digest("hex"));
  for (const path of ["/", "/.dev.vars", "/../.dev.vars", "/phone-inbox.apk?other=.dev.vars"]) {
    assert.equal((await fetch(base + path)).status, 404);
  }
  for (let i = 0; i < 2; i++) {
    const result = await fetch(base + "/phone-inbox.apk");
    assert.equal(result.headers.get("content-type"), "application/vnd.android.package-archive");
    assert.equal(result.headers.get("cache-control"), "no-store");
    assert.deepEqual(Buffer.from(await result.arrayBuffer()), content);
  }
  const head = await fetch(base + "/phone-inbox.apk", { method: "HEAD" });
  assert.equal(head.headers.get("content-length"), String(content.length));
  assert.equal(await head.text(), "");
});

import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { generateKeyPairSync } from "node:crypto";
import { parseEnv } from "node:util";
import { clientSettings } from "../scripts/client-settings.js";
import { pushConfigured } from "../src/push.js";

test("setup exports a private standalone bot config without quotes, rotation or phone credentials", async t => {
  const directory = await mkdtemp(join(tmpdir(), "phone-inbox-config-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const original = 'PHONE_TOKEN="private-phone"\nBOT_TOKEN="private-bot"\n';
  await writeFile(join(directory, ".dev.vars"), original, { mode: 0o600 });
  const output = join(directory, "consumer/bot.env");
  const endpoint = "https://inbox.example.ts.net:10000";
  const cli = fileURLToPath(new URL("../scripts/configure-bot.js", import.meta.url));
  const run = (...extra) => spawnSync(process.execPath, [cli, "--endpoint", endpoint, "--hosting", "always-on", "--output", output, ...extra],
    { cwd: directory, encoding: "utf8", timeout: 10_000 });
  const result = run();
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout + result.stderr, /private-phone|private-bot/);
  assert.equal(await readFile(output, "utf8"), `INBOX_URL=${endpoint}\nHOSTING_MODE=always-on\nBOT_TOKEN=private-bot\n`);
  if (process.platform !== "win32") assert.equal((await stat(output)).mode & 0o777, 0o600);
  const config = await clientSettings({ directory: join(directory, "consumer"), credentials: "bot.env", environment: {} });
  assert.deepEqual(config, { endpoint, hostingMode: "always-on", headers: { Authorization: "Bearer private-bot" } });
  assert.equal(run().status, 1);
  assert.equal(run("--replace").status, 0);
  assert.equal(await readFile(join(directory, ".dev.vars"), "utf8"), original);
});

test("Firebase import preserves bearer values and produces usable private configuration without logging keys", async t => {
  const directory = await mkdtemp(join(tmpdir(), "phone-inbox-push-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const original = 'PHONE_TOKEN="private-phone"\nBOT_TOKEN=private-bot\n';
  await writeFile(join(directory, ".dev.vars"), original, { mode: 0o600 });
  const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048,
    privateKeyEncoding: { type: "pkcs8", format: "pem" }, publicKeyEncoding: { type: "spki", format: "pem" } });
  const account = { type: "service_account", project_id: "inbox-example",
    client_email: "sender@inbox-example.iam.gserviceaccount.com", private_key: privateKey };
  await writeFile(join(directory, "account.json"), JSON.stringify(account), { mode: 0o600 });
  const android = { project_info: { project_id: "inbox-example", project_number: "123456" }, client: [{
    client_info: { mobilesdk_app_id: "1:123456:android:abcdef", android_client_info: { package_name: "dev.howaboua.phonedigest" } },
    api_key: [{ current_key: "AIzaSyExample" }],
  }] };
  await writeFile(join(directory, "android.json"), JSON.stringify(android));
  const cli = fileURLToPath(new URL("../scripts/configure-push.js", import.meta.url));
  const run = (...extra) => spawnSync(process.execPath, [cli, "--android", "android.json", "--account", "account.json", ...extra],
    { cwd: directory, encoding: "utf8", timeout: 10_000 });
  const result = run();
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout + result.stderr, /PRIVATE KEY|private-phone|private-bot|AIzaSy/);
  const output = await readFile(join(directory, ".dev.vars"), "utf8");
  assert.ok(output.startsWith(original));
  const env = parseEnv(output);
  assert.ok(pushConfigured(env));
  assert.deepEqual(JSON.parse(env.FCM_SERVICE_ACCOUNT), account);
  assert.equal(run().status, 1);
  assert.equal(run("--replace").status, 0);
  if (process.platform !== "win32") assert.equal((await stat(join(directory, ".dev.vars"))).mode & 0o777, 0o600);
  android.project_info.project_id = "another-project";
  await writeFile(join(directory, "android.json"), JSON.stringify(android));
  assert.equal(run("--replace").status, 1);
  assert.equal(await readFile(join(directory, ".dev.vars"), "utf8"), output);
});

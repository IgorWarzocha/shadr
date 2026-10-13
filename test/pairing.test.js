import { test } from "node:test";
import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import encodeQR from "qr";
import decodeQR from "qr/decode.js";
import { pairingPayload } from "../scripts/pair-phone.js";

const fixtureText = (await readFile(new URL("./fixtures/pairing-v1.json", import.meta.url), "utf8")).trim();
const fixture = JSON.parse(fixtureText);
const cli = fileURLToPath(new URL("../scripts/pair-phone.js", import.meta.url));

test("pairing payload matches the Android fixture and never includes unrelated secrets", () => {
  const payload = pairingPayload({ ...fixture, endpoint: fixture.endpoint + "/", BOT_TOKEN: "must-not-leak" });
  assert.equal(payload, fixtureText);
  assert.equal(JSON.parse(payload).phoneToken, fixture.phoneToken);
});

test("pairing requires a usable secure server address, not a local or credential-bearing URL", () => {
  for (const endpoint of ["100.64.0.1:8787", "http://example.com", "http://100.128.0.1:8787", "http://example.ts.net.evil.test",
    "http://127.0.0.1:8787", "https://localhost", "https://[::1]", "https://127.2.3.4", "http://0.0.0.0",
    "https://user:secret@example.com", "https://example.com/notifications", "https://example.com/?token=secret",
    "https://example.com/#secret", "https://example.com:0", "https://example.com\\path", " https://example.com", "ftp://example.com"]) {
    assert.throws(() => pairingPayload({ ...fixture, endpoint }), Error, endpoint);
  }
  for (const endpoint of ["https://inbox.example.com", "https://relay.example.ts.net:10000", "http://inbox.example.ts.net:8787", "http://100.127.255.254:8787", "https://100.64.0.1:8787"]) {
    assert.equal(JSON.parse(pairingPayload({ ...fixture, endpoint })).endpoint, endpoint);
  }
});

test("pairing validates credentials, paired Access values and UTF-8 size before rendering", () => {
  for (const phoneToken of [undefined, "", "has space", "line\nbreak", "\u0000", "x".repeat(1025)]) {
    assert.throws(() => pairingPayload({ ...fixture, phoneToken }), /PHONE_TOKEN/);
  }
  const cloud = { ...fixture, endpoint: "https://inbox.example.com", accessClientId: "fake-client", accessClientSecret: "fake-secret" };
  assert.deepEqual(JSON.parse(pairingPayload(cloud)), cloud);
  assert.throws(() => pairingPayload({ ...cloud, accessClientSecret: undefined }), /both ACCESS/);
  assert.throws(() => pairingPayload({ ...cloud, accessClientId: "\t" }), /both ACCESS/);
  assert.throws(() => pairingPayload({ ...cloud, endpoint: fixture.endpoint }), /non-tailnet HTTPS/);
  assert.throws(() => pairingPayload({ ...cloud, accessClientSecret: "é".repeat(1000) }), /2048-byte/);
});

test("QR text round trips without losing Unicode or escaped credential characters", () => {
  const funnel = JSON.parse(pairingPayload({ ...fixture, endpoint: "https://relay.example.ts.net:10000" }));
  assert.deepEqual(funnel, { ...fixture, endpoint: "https://relay.example.ts.net:10000" });
  const payload = pairingPayload({ ...fixture, endpoint: "https://inbox.example.com", accessClientId: "fake-id",
    accessClientSecret: 'fake-quoted-"-unicode-é-secret' });
  const matrix = encodeQR(payload, "raw", { ecc: "medium", border: 4, scale: 4 });
  const data = Uint8Array.from(matrix.flatMap(row => row.flatMap(dark => dark ? [0, 0, 0] : [255, 255, 255])));
  assert.equal(decodeQR({ width: matrix.length, height: matrix.length, data }), payload);
});

async function cliWorkspace(t) {
  const directory = await mkdtemp(join(tmpdir(), "phone-pairing-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const vars = `PHONE_TOKEN="${fixture.phoneToken}"\nBOT_TOKEN="fake-bot-secret-never-export"\n`;
  await writeFile(join(directory, ".dev.vars"), vars, { mode: 0o600 });
  await writeFile(join(directory, ".local.env"), 'BIND_ADDRESS="100.64.0.1"\nPORT="8787"\n', { mode: 0o600 });
  const env = { ...process.env };
  delete env.BIND_ADDRESS;
  delete env.PORT;
  return { directory, vars, run: (...args) => spawnSync(process.execPath, [cli, ...args], {
    cwd: directory, encoding: "utf8", timeout: 10_000, env,
  }) };
}

test("CLI saves a private image without rotating credentials, leaking to logs or overwriting files", async t => {
  const { directory, vars, run } = await cliWorkspace(t);
  const result = run("--output", "pairing.gif");
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout + result.stderr, /pairing-fixture-phone-token|fake-bot-secret-never-export/);
  const image = await readFile(join(directory, "pairing.gif"));
  assert.match(image.subarray(0, 6).toString(), /^GIF8[79]a$/);
  if (process.platform !== "win32") assert.equal((await stat(join(directory, "pairing.gif"))).mode & 0o777, 0o600);
  const again = run("--output", "pairing.gif");
  assert.equal(again.status, 1);
  assert.match(again.stderr, /already exists/);
  assert.deepEqual(await readFile(join(directory, "pairing.gif")), image);
  assert.equal(await readFile(join(directory, ".dev.vars"), "utf8"), vars);
  const funnel = run("--endpoint", "https://relay.example.ts.net:10000", "--output", "funnel.gif");
  assert.equal(funnel.status, 0, funnel.stderr);
  assert.doesNotMatch(funnel.stdout + funnel.stderr, /pairing-fixture-phone-token|fake-bot-secret-never-export/);
  assert.match(run("--output", "pairing.png").stderr, /\.gif/);
});

test("CLI refuses noninteractive secret output, missing credentials and public HTTP", async t => {
  const { directory, run } = await cliWorkspace(t);
  assert.match(run().stderr, /interactive terminal/);
  assert.match(run("--endpoint", "http://example.com", "--output", "pairing.gif").stderr, /require HTTPS/);
  assert.match(run("--credentials", "missing.env", "--output", "pairing.gif").stderr, /Initialise the inbox/);
  await rm(join(directory, ".local.env"));
  assert.match(run("--output", "pairing.gif").stderr, /Loopback/);
  if (process.platform !== "win32") {
    await chmod(join(directory, ".dev.vars"), 0o644);
    assert.match(run("--endpoint", fixture.endpoint, "--output", "pairing.gif").stderr, /chmod 600/);
  }
  await assert.rejects(stat(join(directory, "pairing.gif")), { code: "ENOENT" });
});

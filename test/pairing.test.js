import { test } from "node:test";
import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const cli = fileURLToPath(new URL("../scripts/pair-phone.js", import.meta.url));
const endpoint = "https://relay.example.ts.net:10000";
const phoneToken = "pairing-fixture-phone-token";

async function cliWorkspace(t) {
  const directory = await mkdtemp(join(tmpdir(), "phone-pairing-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const vars = `PHONE_TOKEN="${phoneToken}"\nBOT_TOKEN="fake-bot-secret-never-export"\n`;
  await writeFile(join(directory, ".dev.vars"), vars, { mode: 0o600 });
  await writeFile(join(directory, ".local.env"), 'BIND_ADDRESS="100.64.0.1"\nPORT="8787"\n', { mode: 0o600 });
  const env = { ...process.env };
  delete env.BIND_ADDRESS;
  delete env.PORT;
  delete env.INBOX_URL;
  return { directory, vars, run: (...args) => spawnSync(process.execPath, [cli, ...args], {
    cwd: directory, encoding: "utf8", timeout: 10_000, env,
  }) };
}

test("CLI saves a private image without rotating credentials, leaking to logs or overwriting files", async t => {
  const { directory, vars, run } = await cliWorkspace(t);
  const result = run("--endpoint", endpoint, "--output", "pairing.gif");
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(result.stdout + result.stderr, /pairing-fixture-phone-token|fake-bot-secret-never-export/);
  const image = await readFile(join(directory, "pairing.gif"));
  assert.match(image.subarray(0, 6).toString(), /^GIF8[79]a$/);
  if (process.platform !== "win32") assert.equal((await stat(join(directory, "pairing.gif"))).mode & 0o777, 0o600);
  const again = run("--endpoint", endpoint, "--output", "pairing.gif");
  assert.equal(again.status, 1);
  assert.match(again.stderr, /already exists/);
  assert.deepEqual(await readFile(join(directory, "pairing.gif")), image);
  assert.equal(await readFile(join(directory, ".dev.vars"), "utf8"), vars);
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
    assert.match(run("--endpoint", endpoint, "--output", "pairing.gif").stderr, /chmod 600/);
  }
  await assert.rejects(stat(join(directory, "pairing.gif")), { code: "ENOENT" });
});

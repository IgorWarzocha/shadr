import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { serveApk } from "../scripts/serve-apk.js";

test("APK download serves only the selected artifact, supports repeat requests and rejects unsafe binds", async t => {
  const directory = await mkdtemp(join(tmpdir(), "phone-inbox-ops-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
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

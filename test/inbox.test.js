import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare } from "miniflare";

let runtime, db;
const bindings = {
  PHONE_TOKEN: "test-phone-secret", BOT_TOKEN: "test-bot-secret", MODE: "local",
};
let source;
function options(env = bindings) {
  return { workers: [{ config: {
    name: "inbox-test", compatibilityDate: "2026-07-30",
    manifest: { mainModule: "worker.js", modules: { "worker.js": { type: "esm", contents: source } } },
    env: { ...Object.fromEntries(Object.entries(env).map(([name, value]) => [name, { type: "text", value }])), DB: { type: "d1", id: "test-db" } },
  } }] };
}
async function configure(config) {
  await runtime.setOptions(config);
  db = await runtime.getD1Database("DB");
}
before(async () => {
  const bundle = await build({ entryPoints: ["src/worker.js"], bundle: true, write: false, format: "esm", platform: "browser" });
  source = bundle.outputFiles[0].text;
  runtime = new Miniflare(options());
  db = await runtime.getD1Database("DB");
  for (const file of (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort()) {
    const migration = await readFile("migrations/" + file, "utf8");
    await db.batch(migration.split(";").map(s => s.trim()).filter(Boolean).map(s => db.prepare(s)));
  }
});
after(async () => { await runtime?.dispose(); });
beforeEach(async () => {
  await configure(options());
  await db.batch(["UPDATE shade SET notifications='[]',capturedAt=NULL,receivedAt=NULL", "DELETE FROM dismissals", "DELETE FROM dismissal_log",
    "UPDATE device SET notificationAccessGranted=NULL,sequence=0,requestId=NULL,lastPostAt=NULL,lastListenerAt=NULL,listenerBound=0,timeZone=NULL,startedAt=strftime('%Y-%m-%dT%H:%M:%fZ','now')"]
    .map(sql => db.prepare(sql)));
});
async function request(path, role = "bot", data) {
  const response = await runtime.dispatchFetch("http://localhost" + path, {
    method: data === undefined ? "GET" : "POST",
    headers: { Authorization: "Bearer " + (role === "phone" ? bindings.PHONE_TOKEN : role === "bot" ? bindings.BOT_TOKEN : role) },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  return { status: response.status, data: await response.json() };
}
const item = (key = "one", pkg = "noise.app") => ({ key, package: pkg, appName: "App", title: "Sender", text: "Preview" });
function shade(sequence = 1, notifications = [item()], extra = {}) {
  const now = new Date().toISOString();
  return { sequence, capturedAt: now, lastListenerAt: now, listenerBound: true, timeZone: "Europe/London", notifications, outcomes: [], ...extra };
}
test("role isolation and malformed snapshots cannot mutate shade", async () => {
  assert.equal((await request("/health", "bad")).status, 401);
  assert.equal((await request("/health", "phone")).data.role, "phone");
  assert.equal((await request("/health", "bot")).data.role, "bot");
  assert.equal((await request("/notifications", "phone")).status, 403);
  assert.equal((await request("/notifications", "bot", shade())).status, 403);
  assert.equal((await request("/dismiss", "phone", { keys: ["one"], reason: "noise" })).status, 403);
  assert.equal((await request("/notifications", "phone", shade())).status, 200);
  assert.equal((await request("/notifications", "phone", shade(2, [item(), item()]))).status, 400);
  assert.equal((await request("/notifications", "phone", shade(2, [], { timeZone: "bad" }))).status, 400);
  assert.equal((await request("/notifications")).data.notifications.length, 1);
});
test("latest bound snapshot replaces JSON, stale and unbound posts cannot overwrite it", async () => {
  await request("/notifications", "phone", shade());
  await request("/notifications", "phone", shade(2, [{ ...item(), text: "Updated", firstSeen: "untrusted", actor: "unknown" }]));
  const valid = (await request("/notifications")).data;
  assert.deepEqual(valid.notifications, [{ ...item(), text: "Updated" }]);
  assert.equal((await request("/notifications", "phone", shade(1, []))).status, 409);
  assert.equal((await request("/notifications", "phone", shade(2, []))).status, 409);
  await request("/notifications", "phone", shade(3, [], { listenerBound: false }));
  const unbound = (await request("/notifications")).data;
  assert.deepEqual(unbound.notifications, valid.notifications);
  assert.equal(unbound.capturedAt, valid.capturedAt);
  assert.equal(unbound.receivedAt, valid.receivedAt);
  assert.equal(unbound.status.state, "phone_not_ready");
  assert.equal((await request("/health")).data.sequence, 3);
  await request("/notifications", "phone", shade(4, []));
  assert.deepEqual((await request("/notifications")).data.notifications, []);
  assert.deepEqual((await request("/notifications")).data.dismissalLog, []);
});
test("clearing stays pending until a matching phone receipt and retries settle once", async () => {
  const notification = item("system", "com.android.systemui");
  await request("/notifications", "phone", shade(1, [notification]));
  const payload = { keys: ["system"], reason: "clear" };
  assert.deepEqual((await request("/dismiss", "bot", payload)).data.queued, ["system"]);
  assert.deepEqual((await request("/dismiss", "bot", payload)).data.queued, []);
  const command = (await request("/notifications", "phone", shade(2, [notification]))).data;
  assert.deepEqual(command.dismiss, ["system"]);
  const requestId = command.requestIds.system;
  assert.ok(requestId);
  await request("/notifications", "phone", shade(3, [notification], {
    outcomes: [{ key: "system", requestId: "unknown", status: "dismissed" }],
  }));
  const pending = (await request("/notifications")).data;
  assert.equal(pending.pending[0].requestId, requestId);
  assert.deepEqual(pending.dismissalLog.map(row => row.event), ["requested"]);
  const outcomes = [{ key: "system", requestId, status: "dismissed" }];
  await request("/notifications", "phone", shade(4, [], { outcomes }));
  await request("/notifications", "phone", shade(5, [], { outcomes }));
  const settled = (await request("/notifications")).data;
  assert.deepEqual(settled.pending, []);
  assert.deepEqual(settled.dismissalLog.map(row => row.event), ["requested", "dismissed"]);
});

test("old receipts expire without losing a pending command or its acknowledgement", async () => {
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "clear" });
  const requestId = (await request("/notifications")).data.pending[0].requestId;
  const expired = new Date(Date.now() - 8 * 86400_000).toISOString();
  await db.prepare("UPDATE dismissal_log SET at=?").bind(expired).run();
  const command = (await request("/notifications", "phone", shade(2))).data;
  assert.equal(command.requestIds.one, requestId);
  assert.deepEqual((await request("/notifications")).data.dismissalLog, []);
  assert.equal((await db.prepare("SELECT count(*) AS count FROM dismissal_log").first()).count, 0);
  await request("/notifications", "phone", shade(3, [], {
    outcomes: [{ key: "one", requestId, status: "dismissed" }],
  }));
  const settled = (await request("/notifications")).data;
  assert.deepEqual(settled.pending, []);
  assert.deepEqual(settled.dismissalLog.map(row => row.event), ["dismissed"]);
});

test("Funnel requires bearer authentication and cloud mode refuses missing Access", async () => {
  const origin = "https://inbox.example.ts.net:10000/health";
  assert.equal((await runtime.dispatchFetch(origin)).status, 401);
  const authenticated = await runtime.dispatchFetch(origin, {
    headers: { Authorization: "Bearer " + bindings.BOT_TOKEN },
  });
  assert.equal(authenticated.status, 200);
  assert.equal((await authenticated.json()).role, "bot");
  assert.equal((await runtime.dispatchFetch("https://accidental.example/health", {
    headers: { Authorization: "Bearer " + bindings.BOT_TOKEN },
  })).status, 503);
  await configure(options({ ...bindings, MODE: "cloud" }));
  assert.equal((await request("/health")).status, 503);
  await configure(options({ ...bindings, MODE: "cloud", ACCESS_TEAM: "example", ACCESS_AUD: "audience" }));
  assert.equal((await request("/health")).status, 401);
});

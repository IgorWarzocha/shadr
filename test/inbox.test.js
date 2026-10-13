import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { health } from "../src/health.js";

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
    if (file === "0005_completed_results.sql") {
      await db.batch([
        ...["requested", "dismissed", "missing", "protected"].map((event, i) =>
          db.prepare("INSERT INTO dismissal_log(id,key,package,at,reason,dryRun,event,requestId) VALUES (?,?,?,?,?,0,?,?)")
            .bind(i + 1, "migration", "app", "2020-01-01T00:00:00Z", "reason", event, "request-" + i)),
        db.prepare("INSERT INTO dismissals(key,package,requestedAt,reason,dryRun,requestId) VALUES ('migration','app','2020-01-01T00:00:00Z','reason',0,'pending-id')"),
        db.prepare("UPDATE shade SET notifications=?,capturedAt=?,receivedAt=? WHERE id=1").bind(JSON.stringify([item("migration")]), "capture", "received"),
      ]);
    }
    const migration = await readFile("migrations/" + file, "utf8");
    await db.batch(migration.split(";").map(s => s.trim()).filter(Boolean).map(s => db.prepare(s)));
    if (file === "0005_completed_results.sql") {
      const migrated = await db.prepare("SELECT id,event,requestId FROM dismissal_log ORDER BY id").all();
      assert.deepEqual(migrated.results, ["requested", "applied", "not_found", "failed"].map((event, i) => ({ id: i + 1, event, requestId: "request-" + i })));
      assert.equal((await db.prepare("SELECT requestId FROM dismissals WHERE key='migration'").first()).requestId, "pending-id");
      assert.deepEqual(await db.prepare("SELECT notifications,capturedAt,receivedAt FROM shade WHERE id=1").first(),
        { notifications: JSON.stringify([item("migration")]), capturedAt: "capture", receivedAt: "received" });
      const indexes = await db.prepare("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='dismissal_log' ORDER BY name").all();
      assert.deepEqual(indexes.results.map(row => row.name), ["dismissal_log_at", "dismissal_log_request"]);
    }
  }
});
after(async () => { await runtime?.dispose(); });
beforeEach(async () => {
  await configure(options());
  await db.batch(["UPDATE shade SET notifications='[]',capturedAt=NULL,receivedAt=NULL", "DELETE FROM dismissals", "DELETE FROM dismissal_log",
    "UPDATE device SET notificationAccessGranted=NULL,sequence=0,pushToken=NULL,requestId=NULL,lastPostAt=NULL,lastListenerAt=NULL,listenerBound=0,timeZone=NULL,startedAt=strftime('%Y-%m-%dT%H:%M:%fZ','now')"]
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
  const accepted = await db.prepare("SELECT * FROM device WHERE id=1").first();
  const acceptedAt = Date.parse(accepted.lastPostAt);
  assert.equal((await request("/health")).data.state, "phone_not_ready");
  const ready = { ...accepted, pushToken: "registered-test-token" };
  assert.equal(health(ready, new Date(acceptedAt + 119999), true).state, "ok");
  assert.equal(health(ready, new Date(acceptedAt + 120000), true).state, "phone_idle");
  assert.equal(health({ ...ready, pendingSince: accepted.lastPostAt }, new Date(acceptedAt + 119999), true).state, "ok");
  assert.equal(health({ ...ready, pendingSince: accepted.lastPostAt }, new Date(acceptedAt + 120000), true).state, "phone_offline");
  assert.equal((await request("/notifications", "phone", shade(2, [item(), item()]))).status, 400);
  assert.equal((await request("/notifications", "phone", shade(2, [], { timeZone: "bad" }))).status, 400);
  assert.equal((await request("/notifications")).data.notifications.length, 1);
  for (const pushToken of ["", "bad\ntoken", "bad token", "x".repeat(4097), 123]) {
    assert.equal((await request("/notifications", "phone", shade(2, [item()], { pushToken }))).status, 400);
  }
  await request("/notifications", "phone", shade(2, [item()], { pushToken: "registered-test-token" }));
  await request("/notifications", "phone", shade(3));
  assert.equal((await db.prepare("SELECT pushToken FROM device WHERE id=1").first()).pushToken, "registered-test-token");
  const config = { projectId: "test-project", applicationId: "1:123:android:abcdef", apiKey: "public-api-key", senderId: "123" };
  await configure(options({ ...bindings, FCM_ANDROID_CONFIG: JSON.stringify({ ...config, private_key: "must-not-escape" }) }));
  assert.deepEqual((await request("/health", "phone")).data.pushConfig, config);
  assert.ok(!("pushConfig" in (await request("/health")).data));
  for (const response of [(await request("/health")).data, (await request("/health", "phone")).data, (await request("/notifications")).data]) {
    assert.ok(!JSON.stringify(response).includes("registered-test-token"));
    assert.ok(!JSON.stringify(response).includes("must-not-escape"));
  }
  await request("/notifications", "phone", shade(4, [item()], { pushToken: null }));
  assert.equal((await db.prepare("SELECT pushToken FROM device WHERE id=1").first()).pushToken, null);
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
  for (const query of ["wait=-1", "wait=11", "wait=", "wait=0.5", "wait=0&wait=1"]) {
    assert.equal((await request("/dismiss?" + query, "bot", payload)).status, 400, query);
  }
  const queued = (await request("/dismiss?wait=0", "bot", payload)).data;
  assert.deepEqual(queued.queued, ["system"]);
  assert.deepEqual(queued.completed, []);
  assert.deepEqual(queued.pending, [{ key: "system", requestId: queued.requestIds.system }]);
  assert.deepEqual(queued.delivery, { state: "push_not_configured" });
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
  assert.deepEqual(pending.dismissalLog, []);
  const outcomes = [{ key: "system", requestId, status: "dismissed" }];
  await request("/notifications", "phone", shade(4, [], { outcomes }));
  await request("/notifications", "phone", shade(5, [], { outcomes }));
  const settled = (await request("/notifications")).data;
  assert.deepEqual(settled.pending, []);
  assert.deepEqual(settled.dismissalLog.map(row => row.result), ["applied"]);
  assert.ok(settled.dismissalLog.every(row => !("event" in row)));
  const remaining = [item("refused"), item("gone"), item("legacy-missing"), item("legacy-protected")];
  await request("/notifications", "phone", shade(6, remaining));
  await request("/dismiss", "bot", { keys: remaining.map(row => row.key), reason: "clear" });
  const commands = (await request("/notifications")).data.pending;
  const statuses = { refused: "failed", gone: "not_found", "legacy-missing": "missing", "legacy-protected": "protected" };
  const results = commands.map(({ key, requestId }) => ({ key, requestId, status: statuses[key] }));
  await request("/notifications", "phone", shade(7, remaining, { outcomes: results }));
  await request("/notifications", "phone", shade(8, remaining, { outcomes: results }));
  const finished = (await request("/notifications")).data;
  assert.deepEqual(finished.pending, []);
  assert.deepEqual(Object.fromEntries(finished.dismissalLog.map(row => [row.key, row.result])),
    { system: "applied", refused: "failed", gone: "not_found", "legacy-missing": "not_found", "legacy-protected": "failed" });
  assert.equal(new Set(finished.dismissalLog.map(row => row.requestId)).size, 5);
  await request("/notifications", "phone", shade(9, [item("vanished"), item("reused")]));
  const disappearing = (await request("/dismiss?wait=0", "bot", { keys: ["vanished", "reused"], reason: "clear" })).data;
  await request("/notifications", "phone", shade(10, [item("reused", "different.app")]));
  const absent = (await request("/notifications")).data;
  assert.deepEqual(absent.pending, []);
  assert.deepEqual(absent.dismissalLog.filter(row => row.key === "vanished" || row.key === "reused")
    .map(row => [row.key, row.requestId, row.result]), [
    ["vanished", disappearing.requestIds.vanished, "not_found"], ["reused", disappearing.requestIds.reused, "not_found"],
  ]);
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
    outcomes: [{ key: "one", requestId, status: "applied" }],
  }));
  const settled = (await request("/notifications")).data;
  assert.deepEqual(settled.pending, []);
  assert.deepEqual(settled.dismissalLog.map(row => row.result), ["applied"]);
});

test("compact pages omit bodies and selected reads isolate exact keys and actions", async () => {
  const key = "selected/+?&=#雪";
  const notifications = Array.from({ length: 103 }, (_, i) => item(String(i)));
  notifications[50] = { ...item(key), appName: "", title: "😀".repeat(170), text: "Selected private body" };
  const input = shade(1, notifications);
  assert.equal((await request("/notifications", "phone", input)).status, 200);
  await request("/dismiss", "bot", { keys: [key, "0"], reason: "clear" });
  const full = (await request("/notifications")).data;
  assert.equal(full.notifications.length, 103);
  const first = (await request("/notifications?view=compact")).data;
  assert.equal(first.notifications.length, 50);
  assert.equal(first.total, 103);
  assert.equal(first.nextOffset, 50);
  assert.equal(first.capturedAt, input.capturedAt);
  assert.equal(first.receivedAt, full.receivedAt);
  assert.deepEqual(first.status, full.status);
  assert.deepEqual(Object.keys(first).sort(), ["capturedAt", "nextOffset", "notifications", "receivedAt", "status", "total"]);
  const second = (await request("/notifications?view=compact&limit=50&offset=50")).data;
  assert.deepEqual(second.notifications[0], { app: "noise.app", title: "😀".repeat(160), key });
  for (const row of [...first.notifications, ...second.notifications]) {
    assert.deepEqual(Object.keys(row).sort(), ["app", "key", "title"]);
  }
  assert.equal(second.nextOffset, 100);
  const last = (await request("/notifications?view=compact&offset=100")).data;
  assert.equal(last.notifications.length, 3);
  assert.equal(last.nextOffset, null);
  const empty = (await request("/notifications?view=compact&limit=100&offset=1000")).data;
  assert.deepEqual(empty.notifications, []);
  assert.equal(empty.nextOffset, null);
  const selected = (await request("/notifications?key=" + encodeURIComponent(key))).data;
  assert.deepEqual(selected.notifications, [notifications[50]]);
  assert.deepEqual(selected.pending, full.pending.filter(row => row.key === key));
  assert.deepEqual(selected.dismissalLog, full.dismissalLog.filter(row => row.key === key));
  assert.equal(selected.dryRun, false);
  const requestId = selected.pending[0].requestId;
  await request("/notifications", "phone", shade(2, notifications, { outcomes: [{ key, requestId, status: "applied" }] }));
  const receipt = (await request("/notifications?key=" + encodeURIComponent(key))).data;
  assert.deepEqual(receipt.pending, []);
  assert.deepEqual(receipt.dismissalLog.map(row => [row.key, row.result]), [[key, "applied"]]);
  const missing = (await request("/notifications?key=absent")).data;
  assert.deepEqual([missing.notifications, missing.pending, missing.dismissalLog], [[], [], []]);
  for (const query of ["view=full", "view=", "limit=50", "offset=0", "key=x&view=compact", "key=x&limit=1", "key=x&offset=0", "key=", "key=x&key=y",
    "view=compact&view=compact", "view=compact&limit=0", "view=compact&limit=101", "view=compact&limit=1.5", "view=compact&limit=", "view=compact&limit=1e2",
    "view=compact&offset=-1", "view=compact&offset=1001", "view=compact&offset=0.5", "view=compact&offset=", "view=compact&limit=1&limit=2"]) {
    const invalid = await request("/notifications?" + query);
    assert.equal(invalid.status, 400, query);
    assert.equal(invalid.data.state, "invalid_request", query);
  }
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

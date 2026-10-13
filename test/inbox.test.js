import { test, before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { health } from "../src/health.js";
import { ingest, readInbox } from "../src/inbox.js";
import { isTailnetAddress, isLocalHost } from "../src/local-network.js";

let runtime, db;
const bindings = {
  PHONE_TOKEN: "test-phone-secret", BOT_TOKEN: "test-bot-secret", MODE: "local",
  NOISE_PACKAGES: '["noise.app","protected.app"]', PROTECTED_PACKAGES: '["protected.app"]',
  LIVE_DISMISSALS: "false",
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
  assert.equal((await request("/export", "phone")).status, 404);
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
test("only noise queues, protected wins, dry-run sticks until explicit requeue", async () => {
  await request("/notifications", "phone", shade(1, [item(), item("protected", "protected.app"), item("message", "message.app")]));
  const result = await request("/dismiss", "bot", { keys: ["one", "protected", "message", "gone"], reason: "noise" });
  assert.deepEqual(result.data.queued, ["one"]);
  assert.deepEqual(result.data.ignored, ["protected", "message", "gone"]);
  const suggestion = (await request("/notifications", "phone", shade(2))).data;
  assert.deepEqual(suggestion,
    { dryRun: true, dismiss: [], wouldDismiss: ["one"], requestIds: { one: suggestion.requestIds.one } });
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  assert.equal((await request("/notifications", "phone", shade(3))).data.dryRun, true);
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  assert.deepEqual((await request("/notifications", "phone", shade(4))).data,
    { dryRun: false, dismiss: [], wouldDismiss: ["one"], requestIds: suggestion.requestIds });
  await request("/dismiss", "bot", { keys: ["one"], reason: "approved noise" });
  const command = (await request("/notifications", "phone", shade(5))).data;
  assert.deepEqual(command.dismiss, ["one"]);
  assert.notEqual(command.requestIds.one, suggestion.requestIds.one);
  await request("/notifications", "phone", shade(6, [], { outcomes: [{ key: "one", status: "dismissed", requestId: command.requestIds.one }] }));
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.pending, []);
  assert.ok(inbox.dismissalLog.some(row => row.event === "dismissed" && row.reason === "approved noise"));
});
test("policy removal revokes pending delivery and absent keys leave queue", async () => {
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "noise" });
  await configure(options({ ...bindings, NOISE_PACKAGES: "[]" }));
  assert.deepEqual((await request("/notifications", "phone", shade(2))).data.wouldDismiss, []);
  await request("/notifications", "phone", shade(3, []));
  assert.deepEqual((await request("/notifications")).data.pending, []);
});
test("key reuse by a protected package revokes old noise commands without inventing a result", async () => {
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "noise" });
  const reply = (await request("/notifications", "phone", shade(2, [item("one", "protected.app")]))).data;
  assert.deepEqual(reply.dismiss, []);
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.pending, []);
  assert.deepEqual(inbox.dismissalLog.map(row => row.event), ["requested"]);
});
test("delayed outcomes survive missing snapshots and cannot consume a new lifecycle's request", async () => {
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "first request" });
  const first = (await request("/notifications", "phone", shade(2))).data.requestIds.one;
  await request("/notifications", "phone", shade(3, []));
  assert.deepEqual((await request("/notifications")).data.pending, []);
  await request("/notifications", "phone", shade(4));
  await request("/dismiss", "bot", { keys: ["one"], reason: "new lifecycle" });
  const second = (await request("/notifications")).data.pending[0].requestId;
  const outcomes = [{ key: "one", status: "dismissed", requestId: first }];
  assert.equal((await request("/notifications", "phone", shade(5, [item()], { outcomes }))).status, 200);
  await request("/notifications", "phone", shade(6, [item()], { outcomes }));
  const inbox = (await request("/notifications")).data;
  assert.equal(inbox.pending[0].requestId, second);
  assert.equal(inbox.dismissalLog.filter(row => row.requestId === first && row.event === "dismissed").length, 1);
  assert.equal("actor" in inbox.dismissalLog[0], false);
});
test("multi-key queues assign independent identities and repeated requests do not duplicate history", async () => {
  await request("/notifications", "phone", shade(1, [item("one"), item("two")]));
  const payload = { keys: ["one", "two"], reason: "noise" };
  await request("/dismiss", "bot", payload);
  assert.deepEqual((await request("/dismiss", "bot", payload)).data.queued, []);
  const inbox = (await request("/notifications")).data;
  assert.equal(new Set(inbox.pending.map(row => row.requestId)).size, 2);
  assert.equal(inbox.dismissalLog.length, 2);
});
test("concurrent snapshots cannot restore the older sequence", async () => {
  await Promise.all([request("/notifications", "phone", shade(1)), request("/notifications", "phone", shade(2, []))]);
  const result = (await request("/notifications")).data;
  assert.equal((await request("/health")).data.sequence, 2);
  assert.deepEqual(result.notifications, []);
  assert.equal((await request("/export")).status, 404);
});
test("D1 failure rolls back the entire snapshot, including health and clears", async () => {
  await request("/notifications", "phone", shade());
  await db.prepare("CREATE TRIGGER reject_broken BEFORE UPDATE ON shade WHEN json_extract(NEW.notifications,'$[0].key')='broken' BEGIN SELECT RAISE(ABORT,'test failure'); END").run();
  try {
    assert.equal((await request("/notifications", "phone", shade(2, [item("broken")]))).status, 500);
    const inbox = (await request("/notifications")).data;
    assert.equal((await request("/health")).data.sequence, 1);
    assert.equal(inbox.notifications[0].text, "Preview");
    assert.equal(inbox.dismissalLog.length, 0);
  } finally { await db.prepare("DROP TRIGGER reject_broken").run(); }
});
test("seven-day log pruning preserves old pending commands, retries and late acknowledgements", async () => {
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "noise" });
  const sevenDays = Date.now() - 7 * 86400_000;
  await db.prepare("UPDATE dismissal_log SET at=?").bind(new Date(sevenDays - 1000).toISOString()).run();
  await db.prepare("UPDATE dismissals SET requestedAt=?").bind(new Date(sevenDays - 1000).toISOString()).run();
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.dismissalLog, []);
  assert.equal(inbox.pending.length, 1);
  const requestId = inbox.pending[0].requestId;
  await request("/notifications", "phone", shade(2, [], { listenerBound: false }));
  assert.equal((await db.prepare("SELECT count(*) AS count FROM dismissal_log").first()).count, 0);
  assert.equal((await db.prepare("SELECT count(*) AS count FROM dismissals").first()).count, 1);
  assert.deepEqual((await request("/notifications")).data.notifications, [item()]);
  assert.deepEqual((await request("/dismiss", "bot", { keys: ["one"], reason: "noise" })).data.queued, []);
  const retry = (await request("/notifications", "phone", shade(3))).data;
  assert.deepEqual(retry.dismiss, ["one"]);
  assert.equal(retry.requestIds.one, requestId);
  const outcomes = [{ key: "one", requestId, status: "dismissed" }];
  await request("/notifications", "phone", shade(4, [], { outcomes }));
  await request("/notifications", "phone", shade(5, [], { outcomes }));
  const acknowledged = (await request("/notifications")).data;
  assert.deepEqual(acknowledged.pending, []);
  assert.deepEqual(acknowledged.dismissalLog.map(row => row.event), ["dismissed"]);
});
test("local mode refuses a public hostname even with a valid bearer", async () => {
  const response = await runtime.dispatchFetch("https://accidental.example/health", {
    headers: { Authorization: "Bearer " + bindings.BOT_TOKEN },
  });
  assert.equal(response.status, 503);
});
test("tailnet hostnames work without opening local mode to arbitrary domains", async () => {
  const response = await runtime.dispatchFetch("http://inbox.example.ts.net/health", {
    headers: { Authorization: "Bearer " + bindings.BOT_TOKEN },
  });
  assert.equal(response.status, 200);
  for (const host of ["0.0.0.0", "192.0.2.1", "100.128.0.1", "100.64.0.256", "100.64.0.1.", "example.com", "inbox.ts.net.example.com"]) {
    assert.equal(isTailnetAddress(host), false);
    assert.equal(isLocalHost(host), false);
  }
  for (const host of ["100.64.0.1", "100.127.255.255"]) assert.equal(isTailnetAddress(host), true);
});
test("cloud mode fails closed without configured Access", async () => {
  await configure(options({ ...bindings, MODE: "cloud" }));
  assert.equal((await request("/health")).status, 503);
  await configure(options({ ...bindings, MODE: "cloud", ACCESS_TEAM: "example", ACCESS_AUD: "audience" }));
  assert.equal((await request("/health")).status, 401);
});
test("health uses explicit permission and four-hour POST freshness, never timezone alerts", () => {
  const now = new Date("2026-10-13T12:00:00Z");
  const current = { listenerBound: 1, notificationAccessGranted: 1, lastPostAt: "2026-10-13T08:00:01Z" };
  assert.equal(health({ ...current, lastPostAt: null }, now).state, "not_paired");
  assert.equal(health(current, now).state, "ok");
  assert.equal(health({ ...current, lastPostAt: "2026-10-13T08:00:00Z" }, now).state, "phone_offline");
  assert.equal(health({ ...current, listenerBound: 0, notificationAccessGranted: 0 }, now).state, "notification_access_revoked");
  assert.equal(health({ ...current, listenerBound: 0, notificationAccessGranted: null }, now).state, "phone_not_ready");
  assert.deepEqual(Object.keys(health(current, now)).sort(), ["lastSeenAt", "nextAction", "state"]);
});

test("permission metadata, concise read contracts and safe errors are explicit", async () => {
  assert.deepEqual((await request("/health")).data, {
    state: "not_paired", lastSeenAt: null, nextAction: "Pair the phone and send its first snapshot.", role: "bot", sequence: 0,
  });
  const invalid = await request("/notifications", "phone", shade(1, [], { notificationAccessGranted: "false" }));
  assert.deepEqual(invalid, { status: 400, data: { state: "invalid_request", nextAction: "Check the request." } });
  assert.equal((await request("/notifications", "phone", shade(1, [], { notificationAccessGranted: false }))).status, 400);
  await request("/notifications", "phone", shade(1, [], { listenerBound: false, notificationAccessGranted: false }));
  assert.equal((await request("/health")).data.state, "notification_access_revoked");
  await request("/notifications", "phone", shade(2, [], { listenerBound: false }));
  assert.equal((await request("/health")).data.state, "phone_not_ready");
  await request("/notifications", "phone", shade(3, [item()], { notificationAccessGranted: true }));
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(Object.keys(inbox).sort(), ["capturedAt", "dismissalLog", "dryRun", "notifications", "pending", "receivedAt", "status"]);
  assert.equal(inbox.status.state, "ok");
  assert.deepEqual((await request("/health", "bad")).data, {
    state: "credentials_rejected", nextAction: "Check the configured credentials.",
  });
  assert.equal((await request("/notifications", "phone", shade(3))).data.state, "stale_sequence");
});

test("queue/send never implies dismissal and dry-run or unknown receipts cannot fabricate success", async () => {
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "noise" });
  const first = (await request("/notifications", "phone", shade(2))).data;
  const outcomes = [
    { key: "one", requestId: first.requestIds.one, status: "dismissed" },
    { key: "one", requestId: "unknown-command", status: "dismissed" },
  ];
  await request("/notifications", "phone", shade(3, [item()], { outcomes }));
  let inbox = (await request("/notifications")).data;
  assert.equal(inbox.pending[0].requestId, first.requestIds.one);
  assert.deepEqual(inbox.dismissalLog.map(row => row.event), ["requested"]);
  const retry = (await request("/notifications", "phone", shade(4))).data;
  assert.deepEqual(retry.requestIds, first.requestIds);
  await request("/notifications", "phone", shade(5, []));
  inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.pending, []);
  assert.deepEqual(inbox.dismissalLog.map(row => row.event), ["requested"]);
});

test("request retries and receipt retries acknowledge once without consuming replacement commands", async () => {
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  await request("/notifications", "phone", shade());
  const payload = { keys: ["one"], reason: "noise" };
  await Promise.all([request("/dismiss", "bot", payload), request("/dismiss", "bot", payload)]);
  const first = (await request("/notifications", "phone", shade(2))).data;
  assert.deepEqual(first.dismiss, ["one"]);
  assert.deepEqual((await request("/notifications")).data.dismissalLog.map(row => row.event), ["requested"]);
  assert.deepEqual((await request("/notifications", "phone", shade(3))).data.requestIds, first.requestIds);
  const outcomes = [{ key: "one", requestId: first.requestIds.one, status: "dismissed" }];
  await request("/notifications", "phone", shade(4, [], { outcomes }));
  assert.equal((await request("/notifications", "phone", shade(4, [], { outcomes }))).status, 409);
  await request("/notifications", "phone", shade(5, [], { outcomes }));
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.pending, []);
  assert.deepEqual(inbox.dismissalLog.map(row => row.event), ["requested", "dismissed"]);
});

test("receipt retention uses relay receipt time and keeps the exact seven-day boundary", async () => {
  await configure(options({ ...bindings, LIVE_DISMISSALS: "true" }));
  await db.prepare("UPDATE device SET startedAt='2020-01-01T00:00:00.000Z'").run();
  await request("/notifications", "phone", shade());
  await request("/dismiss", "bot", { keys: ["one"], reason: "noise" });
  const command = (await request("/notifications", "phone", shade(2))).data;
  const received = new Date();
  await ingest(db, { ...bindings, LIVE_DISMISSALS: "true" }, shade(3, [], {
    capturedAt: "2020-01-01T00:00:00.000Z", lastListenerAt: "2020-01-01T00:00:00.000Z",
    outcomes: [{ key: "one", requestId: command.requestIds.one, status: "dismissed" }],
  }), received);
  const receipt = (await readInbox(db, bindings, received)).dismissalLog.find(row => row.event === "dismissed");
  assert.equal(receipt.at, received.toISOString());
  const boundary = new Date(received.getTime() + 7 * 86400_000);
  assert.equal((await readInbox(db, bindings, boundary)).dismissalLog.filter(row => row.event === "dismissed").length, 1);
  assert.equal((await readInbox(db, bindings, new Date(boundary.getTime() + 1))).dismissalLog.length, 0);
  await ingest(db, bindings, shade(4, [], { listenerBound: false }), new Date(boundary.getTime() + 1));
  assert.equal((await db.prepare("SELECT count(*) AS count FROM dismissal_log").first()).count, 0);
});

test("pending queue stays bounded across shade replacements and unbound health posts", async () => {
  const many = Array.from({ length: 1000 }, (_, i) => item("old-" + i));
  assert.equal((await request("/notifications", "phone", shade(1, [...many, item("overflow")]))).status, 400);
  await request("/notifications", "phone", shade(1, many));
  for (let start = 0; start < many.length; start += 100) {
    await request("/dismiss", "bot", { keys: many.slice(start, start + 100).map(row => row.key), reason: "noise" });
  }
  assert.equal((await request("/notifications")).data.pending.length, 1000);
  await request("/notifications", "phone", shade(2, [], { listenerBound: false }));
  assert.equal((await request("/notifications")).data.pending.length, 1000);
  await request("/notifications", "phone", shade(3, [item("new")]));
  await request("/dismiss", "bot", { keys: ["new"], reason: "noise" });
  const inbox = (await request("/notifications")).data;
  assert.deepEqual(inbox.pending.map(row => row.key), ["new"]);
  assert.ok(inbox.dismissalLog.every(row => row.event === "requested"));
});

test("forward migration retains current shade and correlated recent receipts, not old item/history rows", async () => {
  const config = options();
  config.workers[0].config.env.DB.id = "migration-db";
  const upgrade = new Miniflare(config);
  try {
    const old = await upgrade.getD1Database("DB");
    const files = (await readdir("migrations")).filter(name => name.endsWith(".sql")).sort();
    const apply = async file => {
      const sql = await readFile("migrations/" + file, "utf8");
      await old.batch(sql.split(";").map(s => s.trim()).filter(Boolean).map(s => old.prepare(s)));
    };
    for (const file of files.slice(0, 3)) await apply(file);
    const now = new Date().toISOString();
    await old.batch([
      old.prepare("UPDATE device SET sequence=42,lastPostAt=?,capturedAt=?,listenerBound=0,lastListenerAt=?,timeZone='Europe/London'").bind(now, now, now),
      old.prepare("INSERT INTO notifications VALUES ('live','noise.app','App','Title','Preview',?,1,NULL)").bind(now),
      old.prepare("INSERT INTO notifications VALUES ('cleared','noise.app','App','','',?,0,?)").bind(now, now),
      old.prepare("INSERT INTO dismissals VALUES ('live','noise.app','2020-01-01T00:00:00.000Z','noise',0,'keep-command')"),
      old.prepare("INSERT INTO dismissal_log(key,package,at,actor,reason,dryRun,event,requestId) VALUES ('live','noise.app','2020-01-01T00:00:00.000Z','bot','noise',0,'requested','keep-command')"),
      old.prepare("INSERT INTO dismissal_log(key,package,at,actor,reason,dryRun,event) VALUES ('cleared','noise.app',?,'unknown','shade removed',0,'cleared')").bind(now),
      old.prepare("INSERT INTO dismissal_log(key,package,at,actor,reason,dryRun,event,requestId) VALUES ('old','noise.app','2020-01-01T00:00:00.000Z','bot','noise',0,'dismissed','expired')"),
    ]);
    await apply(files[3]);
    const result = await readInbox(old, bindings, new Date());
    assert.deepEqual(result.notifications, [{ key: "live", package: "noise.app", appName: "App", title: "Title", text: "Preview" }]);
    assert.equal(result.capturedAt, null);
    assert.equal(result.receivedAt, null);
    assert.equal(result.pending[0].requestId, "keep-command");
    assert.deepEqual(result.dismissalLog, []);
    assert.equal((await old.prepare("SELECT sequence FROM device").first()).sequence, 42);
    assert.equal(await old.prepare("SELECT name FROM sqlite_master WHERE name='notifications'").first(), null);
    assert.equal((await old.prepare("PRAGMA table_info(dismissal_log)").all()).results.some(row => row.name === "actor"), false);
    const outcomes = [{ key: "live", requestId: "keep-command", status: "dismissed" }];
    await ingest(old, bindings, shade(43, [], { outcomes }), new Date());
    const acknowledged = await readInbox(old, bindings, new Date());
    assert.deepEqual(acknowledged.dismissalLog.map(row => row.event), ["dismissed"]);
    assert.deepEqual(acknowledged.pending, []);
  } finally { await upgrade.dispose(); }
});

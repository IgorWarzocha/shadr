import { HttpError, policy } from "./contract.js";
import { health } from "./health.js";

const RETENTION_MS = 7 * 86400_000;
const cutoff = now => new Date(now - RETENTION_MS).toISOString();

export async function device(db) {
  return db.prepare("SELECT * FROM device WHERE id=1").first();
}
export async function ingest(db, env, input, now) {
  const previous = await device(db);
  if (input.sequence <= previous.sequence) throw new HttpError(409, "Stale sequence");
  const at = now.toISOString();
  const winner = crypto.randomUUID();
  const guard = "EXISTS (SELECT 1 FROM device WHERE requestId=?)";
  const statements = [db.prepare(
    "UPDATE device SET sequence=?,requestId=?,lastPostAt=?,capturedAt=?,listenerBound=?,lastListenerAt=?,timeZone=?,notificationAccessGranted=? WHERE id=1 AND sequence<?"
  ).bind(input.sequence, winner, at, input.capturedAt, Number(input.listenerBound),
    input.lastListenerAt, input.timeZone, input.notificationAccessGranted === undefined ? null : Number(input.notificationAccessGranted), input.sequence)];
  const add = (sql, ...args) => statements.push(db.prepare(sql).bind(...args, winner));
  // D1 batches are transactional. Only the winning sequence may change any state.
  add(`DELETE FROM dismissal_log WHERE at<? AND ${guard}`, cutoff(now));
  const outcomes = JSON.stringify(input.outcomes);
  add(`WITH requests AS (
    SELECT key,package,reason,dryRun,requestId FROM dismissals
    UNION SELECT key,package,reason,dryRun,requestId FROM dismissal_log WHERE event='requested'
  )
    INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
    SELECT d.key,d.package,?,d.reason,d.dryRun,json_extract(o.value,'$.status'),d.requestId
    FROM requests d JOIN json_each(?) o ON d.key=json_extract(o.value,'$.key')
    AND d.requestId=json_extract(o.value,'$.requestId')
    WHERE (d.dryRun=0 OR json_extract(o.value,'$.status')!='dismissed')
    AND NOT EXISTS (SELECT 1 FROM dismissal_log l WHERE l.requestId=d.requestId AND l.key=d.key
      AND l.event IN ('dismissed','missing','protected')) AND ${guard}`, at, outcomes);
  // A receipt must actually have been accepted, not merely supplied by a phone.
  add(`DELETE FROM dismissals WHERE EXISTS
    (SELECT 1 FROM dismissal_log l WHERE l.key=dismissals.key AND l.requestId=dismissals.requestId
      AND l.event IN ('dismissed','missing','protected')) AND ${guard}`);
  if (input.listenerBound) {
    const encoded = JSON.stringify(input.notifications);
    add(`UPDATE shade SET notifications=?,capturedAt=?,receivedAt=? WHERE id=1 AND ${guard}`,
      encoded, input.capturedAt, at);
    // Absence or key reuse by a different package ends delivery, never infers a result.
    add(`DELETE FROM dismissals WHERE NOT EXISTS
      (SELECT 1 FROM json_each(?) WHERE json_extract(value,'$.key')=dismissals.key
        AND json_extract(value,'$.package')=dismissals.package) AND ${guard}`, encoded);
  }
  const result = await db.batch(statements);
  if (result[0].meta.changes === 0) throw new HttpError(409, "Stale sequence");
  const currentPolicy = policy(env, previous, now);
  const pending = await db.prepare("SELECT key,package,dryRun,requestId FROM dismissals ORDER BY requestedAt,key").all();
  const allowed = pending.results.filter(item => currentPolicy.noise.includes(item.package));
  return {
    dryRun: currentPolicy.dryRun,
    dismiss: input.listenerBound && !currentPolicy.dryRun ? allowed.filter(item => !item.dryRun).map(item => item.key) : [],
    wouldDismiss: allowed.filter(item => currentPolicy.dryRun || item.dryRun).map(item => item.key),
    requestIds: Object.fromEntries(allowed.map(item => [item.key, item.requestId])),
  };
}

export async function queue(db, env, input, now) {
  const currentPolicy = policy(env, await device(db), now);
  const at = now.toISOString();
  const commands = input.keys.map(key => ({ key, requestId: crypto.randomUUID() }));
  // Pending is a subset of the last bound shade (at most 1000 unique keys).
  // Bound ingest prunes absent keys atomically before another queue can run.
  const eligible = `FROM shade s,json_each(s.notifications) n
    JOIN json_each(?) q ON json_extract(n.value,'$.key')=json_extract(q.value,'$.key')
    LEFT JOIN dismissals d ON json_extract(n.value,'$.key')=d.key
    WHERE json_extract(n.value,'$.package') IN (SELECT value FROM json_each(?))
    AND (d.key IS NULL OR (d.dryRun=1 AND ?=0))`;
  const args = [JSON.stringify(commands), JSON.stringify(currentPolicy.noise), Number(currentPolicy.dryRun)];
  const results = await db.batch([
    db.prepare("DELETE FROM dismissal_log WHERE at<?").bind(cutoff(now)),
    db.prepare(`INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
      SELECT json_extract(n.value,'$.key'),json_extract(n.value,'$.package'),?,?,?,'requested',json_extract(q.value,'$.requestId') ${eligible}`)
      .bind(at, input.reason, Number(currentPolicy.dryRun), ...args),
    db.prepare(`INSERT INTO dismissals(key,package,requestedAt,reason,dryRun,requestId)
      SELECT json_extract(n.value,'$.key'),json_extract(n.value,'$.package'),?,?,?,json_extract(q.value,'$.requestId') ${eligible}
      ON CONFLICT(key) DO UPDATE SET requestedAt=excluded.requestedAt,reason=excluded.reason,dryRun=0,requestId=excluded.requestId
      RETURNING key`).bind(at, input.reason, Number(currentPolicy.dryRun), ...args),
  ]);
  const queued = results[2].results.map(row => row.key);
  return { dryRun: currentPolicy.dryRun, queued, ignored: input.keys.filter(key => !queued.includes(key)) };
}

export async function readInbox(db, env, now) {
  const result = await db.batch([
    db.prepare("SELECT * FROM device WHERE id=1"),
    db.prepare("SELECT * FROM shade WHERE id=1"),
    db.prepare("SELECT * FROM dismissals ORDER BY requestedAt,key"),
    db.prepare("SELECT key,package,at,reason,dryRun,event,requestId FROM dismissal_log WHERE at>=? ORDER BY id")
      .bind(cutoff(now)),
  ]);
  const current = result[0].results[0];
  const shade = result[1].results[0];
  const currentPolicy = policy(env, current, now);
  return { notifications: JSON.parse(shade.notifications), capturedAt: shade.capturedAt, receivedAt: shade.receivedAt,
    pending: result[2].results.filter(item => currentPolicy.noise.includes(item.package))
      .map(item => ({ ...item, dryRun: Number(currentPolicy.dryRun || item.dryRun) })),
    dismissalLog: result[3].results, dryRun: currentPolicy.dryRun, status: health(current, now) };
}

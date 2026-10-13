import { HttpError } from "./contract.js";
import { health } from "./health.js";

const RETENTION_MS = 7 * 86400_000;
const cutoff = now => new Date(now - RETENTION_MS).toISOString();
const DEVICE_QUERY = "SELECT device.*,(SELECT min(requestedAt) FROM dismissals) AS pendingSince FROM device WHERE id=1";

export async function device(db) {
  return db.prepare(DEVICE_QUERY).first();
}
export async function ingest(db, input, now) {
  const previous = await device(db);
  if (input.sequence <= previous.sequence) throw new HttpError(409, "Stale sequence");
  const at = now.toISOString();
  const winner = crypto.randomUUID();
  const guard = "EXISTS (SELECT 1 FROM device WHERE requestId=?)";
  const statements = [db.prepare(
    "UPDATE device SET sequence=?,requestId=?,lastPostAt=?,capturedAt=?,listenerBound=?,lastListenerAt=?,timeZone=?,notificationAccessGranted=?,pushToken=CASE WHEN ? THEN ? ELSE pushToken END WHERE id=1 AND sequence<?"
  ).bind(input.sequence, winner, at, input.capturedAt, Number(input.listenerBound),
    input.lastListenerAt, input.timeZone, input.notificationAccessGranted === undefined ? null : Number(input.notificationAccessGranted),
    Number(input.pushToken !== undefined), input.pushToken ?? null, input.sequence)];
  const add = (sql, ...args) => statements.push(db.prepare(sql).bind(...args, winner));
  // D1 batches are transactional. Only the winning sequence may change any state.
  add(`DELETE FROM dismissal_log WHERE at<? AND ${guard}`, cutoff(now));
  const outcomes = JSON.stringify(input.outcomes);
  add(`WITH requests AS (
    SELECT key,package,reason,requestId FROM dismissals
    UNION SELECT key,package,reason,requestId FROM dismissal_log WHERE event='requested'
  )
    INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
    SELECT d.key,d.package,?,d.reason,0,json_extract(o.value,'$.status'),d.requestId
    FROM requests d JOIN json_each(?) o ON d.key=json_extract(o.value,'$.key')
    AND d.requestId=json_extract(o.value,'$.requestId')
    WHERE NOT EXISTS (SELECT 1 FROM dismissal_log l WHERE l.requestId=d.requestId AND l.key=d.key
      AND l.event IN ('applied','failed','not_found')) AND ${guard}`, at, outcomes);
  // A receipt must actually have been accepted, not merely supplied by a phone.
  add(`DELETE FROM dismissals WHERE EXISTS
    (SELECT 1 FROM dismissal_log l WHERE l.key=dismissals.key AND l.requestId=dismissals.requestId
      AND l.event IN ('applied','failed','not_found')) AND ${guard}`);
  if (input.listenerBound) {
    const encoded = JSON.stringify(input.notifications);
    add(`UPDATE shade SET notifications=?,capturedAt=?,receivedAt=? WHERE id=1 AND ${guard}`,
      encoded, input.capturedAt, at);
    // A bound snapshot authoritatively ends delivery for an absent or reused key,
    // but never claims that the bot applied a cancellation.
    add(`INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
      SELECT key,package,?,reason,0,'not_found',requestId FROM dismissals WHERE NOT EXISTS
      (SELECT 1 FROM json_each(?) WHERE json_extract(value,'$.key')=dismissals.key
        AND json_extract(value,'$.package')=dismissals.package) AND ${guard}`, at, encoded);
    add(`DELETE FROM dismissals WHERE NOT EXISTS
      (SELECT 1 FROM json_each(?) WHERE json_extract(value,'$.key')=dismissals.key
        AND json_extract(value,'$.package')=dismissals.package) AND ${guard}`, encoded);
  }
  const result = await db.batch(statements);
  if (result[0].meta.changes === 0) throw new HttpError(409, "Stale sequence");
  const pending = await db.prepare("SELECT key,requestId FROM dismissals ORDER BY requestedAt,key").all();
  return {
    dryRun: false,
    dismiss: input.listenerBound ? pending.results.map(item => item.key) : [],
    wouldDismiss: [],
    requestIds: Object.fromEntries(pending.results.map(item => [item.key, item.requestId])),
  };
}

export async function queue(db, input, now) {
  const at = now.toISOString();
  const commands = input.keys.map(key => ({ key, requestId: crypto.randomUUID() }));
  // Pending is a subset of the last bound shade (at most 1000 unique keys).
  // Bound ingest prunes absent keys atomically before another queue can run.
  const eligible = `FROM shade s,json_each(s.notifications) n
    JOIN json_each(?) q ON json_extract(n.value,'$.key')=json_extract(q.value,'$.key')
    LEFT JOIN dismissals d ON json_extract(n.value,'$.key')=d.key
    WHERE d.key IS NULL`;
  const commandsJson = JSON.stringify(commands);
  const results = await db.batch([
    db.prepare("DELETE FROM dismissal_log WHERE at<?").bind(cutoff(now)),
    db.prepare(`INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
      SELECT json_extract(n.value,'$.key'),json_extract(n.value,'$.package'),?,?,0,'requested',json_extract(q.value,'$.requestId') ${eligible}`)
      .bind(at, input.reason, commandsJson),
    db.prepare(`INSERT INTO dismissals(key,package,requestedAt,reason,dryRun,requestId)
      SELECT json_extract(n.value,'$.key'),json_extract(n.value,'$.package'),?,?,0,json_extract(q.value,'$.requestId') ${eligible}
      RETURNING key`).bind(at, input.reason, commandsJson),
    db.prepare("SELECT key,requestId FROM dismissals WHERE key IN (SELECT value FROM json_each(?)) ORDER BY key")
      .bind(JSON.stringify(input.keys)),
  ]);
  const queued = results[2].results.map(row => row.key);
  return { dryRun: false, queued, ignored: input.keys.filter(key => !queued.includes(key)),
    requestIds: Object.fromEntries(results[3].results.map(row => [row.key, row.requestId])) };
}

export async function dismissalState(db, requestIds) {
  const commands = JSON.stringify(Object.entries(requestIds).map(([key, requestId]) => ({ key, requestId })));
  const result = await db.batch([
    db.prepare(`SELECT l.key,l.requestId,l.event AS result FROM dismissal_log l JOIN json_each(?) q
      ON l.key=json_extract(q.value,'$.key') AND l.requestId=json_extract(q.value,'$.requestId')
      WHERE l.event IN ('applied','failed','not_found') ORDER BY l.id`).bind(commands),
    db.prepare(`SELECT d.key,d.requestId FROM dismissals d JOIN json_each(?) q
      ON d.key=json_extract(q.value,'$.key') AND d.requestId=json_extract(q.value,'$.requestId') ORDER BY d.key`).bind(commands),
  ]);
  return { completed: result[0].results, pending: result[1].results };
}

export async function readInbox(db, now, options = {}, pushReady = false) {
  if (options.compact) {
    const result = await db.batch([
      db.prepare(DEVICE_QUERY),
      db.prepare("SELECT capturedAt,receivedAt,json_array_length(notifications) AS total FROM shade WHERE id=1"),
      db.prepare(`SELECT COALESCE(NULLIF(json_extract(n.value,'$.appName'),''),json_extract(n.value,'$.package')) AS app,
        substr(json_extract(n.value,'$.title'),1,160) AS title,json_extract(n.value,'$.key') AS key
        FROM shade s,json_each(s.notifications) n WHERE s.id=1 ORDER BY CAST(n.key AS INTEGER) LIMIT ? OFFSET ?`)
        .bind(options.limit, options.offset),
    ]);
    const shade = result[1].results[0];
    return { notifications: result[2].results, capturedAt: shade.capturedAt, receivedAt: shade.receivedAt,
      status: health(result[0].results[0], now, pushReady), total: shade.total,
      nextOffset: options.offset + options.limit < shade.total ? options.offset + options.limit : null };
  }
  const selected = options.key !== undefined;
  const result = await db.batch([
    db.prepare(DEVICE_QUERY),
    db.prepare("SELECT capturedAt,receivedAt FROM shade WHERE id=1"),
    selected
      ? db.prepare("SELECT n.value FROM shade s,json_each(s.notifications) n WHERE s.id=1 AND json_extract(n.value,'$.key')=?").bind(options.key)
      : db.prepare("SELECT value FROM shade s,json_each(s.notifications) WHERE s.id=1 ORDER BY CAST(json_each.key AS INTEGER)"),
    selected
      ? db.prepare("SELECT * FROM dismissals WHERE key=? ORDER BY requestedAt,key").bind(options.key)
      : db.prepare("SELECT * FROM dismissals ORDER BY requestedAt,key"),
    db.prepare(`SELECT key,package,at,reason,dryRun,event AS result,requestId FROM dismissal_log
      WHERE at>=? AND event IN ('applied','failed','not_found')${selected ? " AND key=?" : ""} ORDER BY id`)
      .bind(...(selected ? [cutoff(now), options.key] : [cutoff(now)])),
  ]);
  const current = result[0].results[0];
  const shade = result[1].results[0];
  return { notifications: result[2].results.map(row => JSON.parse(row.value)), capturedAt: shade.capturedAt, receivedAt: shade.receivedAt,
    pending: result[3].results.map(item => ({ ...item, dryRun: 0 })),
    dismissalLog: result[4].results, dryRun: false, status: health(current, now, pushReady) };
}

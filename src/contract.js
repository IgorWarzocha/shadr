export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
export function requireThat(ok, message) {
  if (!ok) throw new HttpError(400, message);
}
function string(value, max, empty = false) {
  return typeof value === "string" && value.length <= max && (empty || value.length > 0);
}
function timestamp(value) {
  return typeof value === "string" && /^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value));
}
export async function body(request) {
  // Bound the stream itself. Content-Length is not trustworthy.
  const reader = request.body?.getReader();
  requireThat(reader, "JSON body required");
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 2_000_000) { await reader.cancel(); throw new HttpError(413, "Snapshot too large"); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new HttpError(400, "Invalid JSON"); }
}
export function snapshot(value) {
  requireThat(value && typeof value === "object", "Snapshot required");
  requireThat(Number.isSafeInteger(value.sequence) && value.sequence > 0, "Invalid sequence");
  requireThat(timestamp(value.capturedAt) && Date.parse(value.capturedAt) <= Date.now() + 300_000, "Invalid capturedAt");
  requireThat(typeof value.listenerBound === "boolean", "Invalid listenerBound");
  requireThat(value.notificationAccessGranted === undefined || typeof value.notificationAccessGranted === "boolean", "Invalid notificationAccessGranted");
  requireThat(!value.listenerBound || value.notificationAccessGranted !== false, "Bound listener requires permission");
  requireThat(value.lastListenerAt === null || timestamp(value.lastListenerAt), "Invalid lastListenerAt");
  requireThat(!value.listenerBound || value.lastListenerAt !== null, "Bound listener needs lastListenerAt");
  requireThat(value.lastListenerAt === null || Date.parse(value.lastListenerAt) <= Date.parse(value.capturedAt), "lastListenerAt exceeds capture");
  requireThat(string(value.timeZone, 100), "Invalid timeZone");
  try { new Intl.DateTimeFormat("en", { timeZone: value.timeZone }); }
  catch { throw new HttpError(400, "Unknown timeZone"); }
  requireThat(Array.isArray(value.notifications) && value.notifications.length <= 1000, "Invalid notifications");
  const keys = new Set();
  for (const item of value.notifications) {
    requireThat(item && string(item.key, 1024) && string(item.package, 255) &&
      string(item.appName, 512, true) && string(item.title, 8192, true) && string(item.text, 32768, true), "Invalid notification");
    requireThat(!keys.has(item.key), "Duplicate notification key");
    keys.add(item.key);
  }
  requireThat(Array.isArray(value.outcomes) && value.outcomes.length <= 1000, "Invalid outcomes");
  const outcomes = new Set();
  for (const item of value.outcomes) {
    requireThat(item && string(item.key, 1024) && string(item.requestId, 128) &&
      ["dismissed", "missing", "protected"].includes(item.status), "Invalid outcome");
    requireThat(!outcomes.has(item.requestId), "Duplicate outcome");
    outcomes.add(item.requestId);
  }
  return { ...value, notifications: value.notifications.map(({ key, package: pkg, appName, title, text }) =>
    ({ key, package: pkg, appName, title, text })) };
}
export function dismissal(value) {
  requireThat(value && Array.isArray(value.keys) && value.keys.length > 0 && value.keys.length <= 100, "Expected 1–100 keys");
  requireThat(value.keys.every(key => string(key, 1024)) && new Set(value.keys).size === value.keys.length, "Invalid keys");
  requireThat(string(value.reason, 1000), "Reason required");
  return value;
}

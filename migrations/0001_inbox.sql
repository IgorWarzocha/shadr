CREATE TABLE device (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  sequence INTEGER NOT NULL DEFAULT 0,
  requestId TEXT,
  startedAt TEXT NOT NULL,
  lastPostAt TEXT,
  capturedAt TEXT,
  listenerBound INTEGER NOT NULL DEFAULT 0,
  lastListenerAt TEXT,
  timeZone TEXT
);
INSERT INTO device (id, startedAt) VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
CREATE TABLE notifications (
  key TEXT PRIMARY KEY,
  package TEXT NOT NULL,
  appName TEXT NOT NULL,
  title TEXT NOT NULL,
  text TEXT NOT NULL,
  firstSeen TEXT NOT NULL,
  stillPresent INTEGER NOT NULL,
  clearedAt TEXT
);
CREATE TABLE dismissals (
  key TEXT PRIMARY KEY,
  package TEXT NOT NULL,
  requestedAt TEXT NOT NULL,
  reason TEXT NOT NULL,
  dryRun INTEGER NOT NULL
);
CREATE TABLE dismissal_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL,
  package TEXT NOT NULL,
  at TEXT NOT NULL,
  actor TEXT NOT NULL,
  reason TEXT NOT NULL,
  dryRun INTEGER NOT NULL
);
CREATE INDEX dismissal_log_at ON dismissal_log(at);

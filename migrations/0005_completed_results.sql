CREATE TABLE dismissal_results (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL,
  package TEXT NOT NULL,
  at TEXT NOT NULL,
  reason TEXT NOT NULL,
  dryRun INTEGER NOT NULL CHECK (dryRun IN (0,1)),
  event TEXT NOT NULL CHECK (event IN ('requested','applied','failed','not_found')),
  requestId TEXT NOT NULL
);
INSERT INTO dismissal_results(id,key,package,at,reason,dryRun,event,requestId)
SELECT id,key,package,at,reason,dryRun,
  CASE event WHEN 'dismissed' THEN 'applied' WHEN 'missing' THEN 'not_found'
    WHEN 'protected' THEN 'failed' ELSE event END,requestId FROM dismissal_log;
DROP TABLE dismissal_log;
ALTER TABLE dismissal_results RENAME TO dismissal_log;
CREATE INDEX dismissal_log_at ON dismissal_log(at);
CREATE INDEX dismissal_log_request ON dismissal_log(requestId,key,event);
ALTER TABLE device ADD COLUMN pushToken TEXT;

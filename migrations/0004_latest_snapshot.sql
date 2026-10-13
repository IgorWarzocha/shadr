ALTER TABLE device ADD COLUMN notificationAccessGranted INTEGER CHECK (notificationAccessGranted IN (0,1));
CREATE TABLE shade (
  id INTEGER PRIMARY KEY CHECK (id=1),
  notifications TEXT NOT NULL CHECK (json_valid(notifications)),
  capturedAt TEXT,
  receivedAt TEXT
);
INSERT INTO shade(id,notifications,capturedAt,receivedAt)
SELECT 1,coalesce((SELECT json_group_array(json_object('key',key,'package',package,
  'appName',appName,'title',title,'text',text)) FROM notifications WHERE stillPresent=1),'[]'),
  CASE WHEN listenerBound=1 THEN capturedAt END,
  CASE WHEN listenerBound=1 THEN lastPostAt END FROM device WHERE id=1;
DROP TABLE notifications;
CREATE TABLE dismissal_receipts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL,
  package TEXT NOT NULL,
  at TEXT NOT NULL,
  reason TEXT NOT NULL,
  dryRun INTEGER NOT NULL CHECK (dryRun IN (0,1)),
  event TEXT NOT NULL CHECK (event IN ('requested','dismissed','missing','protected')),
  requestId TEXT NOT NULL
);
INSERT INTO dismissal_receipts(key,package,at,reason,dryRun,event,requestId)
SELECT key,package,at,reason,dryRun,event,requestId FROM dismissal_log
WHERE event!='cleared' AND requestId IS NOT NULL AND requestId!=''
  AND at>=strftime('%Y-%m-%dT%H:%M:%fZ','now','-7 days');
DROP TABLE dismissal_log;
ALTER TABLE dismissal_receipts RENAME TO dismissal_log;
CREATE INDEX dismissal_log_at ON dismissal_log(at);
CREATE INDEX dismissal_log_request ON dismissal_log(requestId,key,event);
DELETE FROM dismissals WHERE NOT EXISTS (SELECT 1 FROM shade,json_each(shade.notifications)
    WHERE json_extract(value,'$.key')=dismissals.key AND json_extract(value,'$.package')=dismissals.package)
  OR key NOT IN (SELECT key FROM dismissals ORDER BY requestedAt DESC,key LIMIT 1000);
INSERT INTO dismissal_log(key,package,at,reason,dryRun,event,requestId)
SELECT key,package,requestedAt,reason,dryRun,'requested',requestId FROM dismissals d
WHERE requestedAt>=strftime('%Y-%m-%dT%H:%M:%fZ','now','-7 days')
  AND NOT EXISTS (SELECT 1 FROM dismissal_log l WHERE l.key=d.key AND l.requestId=d.requestId AND l.event='requested');

ALTER TABLE dismissals ADD COLUMN requestId TEXT NOT NULL DEFAULT '';
ALTER TABLE dismissal_log ADD COLUMN requestId TEXT;
UPDATE dismissals SET requestId=lower(hex(randomblob(16)));
CREATE INDEX dismissal_log_request ON dismissal_log(requestId,key,event);

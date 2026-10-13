ALTER TABLE dismissal_log ADD COLUMN event TEXT NOT NULL DEFAULT 'requested'
  CHECK (event IN ('requested','dismissed','missing','protected','cleared'));

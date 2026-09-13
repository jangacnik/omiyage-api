ALTER TABLE shipments ADD COLUMN title varchar(100);
ALTER TABLE shipments ADD COLUMN shipped_at timestamptz;
ALTER TABLE shipments ADD COLUMN arrived_at timestamptz;

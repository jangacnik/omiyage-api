-- Image bytes move to object storage (Garage); the column stays until existing rows are migrated.
ALTER TABLE media ALTER COLUMN data DROP NOT NULL;

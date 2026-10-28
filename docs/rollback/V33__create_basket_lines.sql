-- Rollback for V33. Purely additive migration: no existing table is touched, so dropping the new
-- table restores the previous schema. Also run: DELETE FROM flyway_schema_history WHERE version = '33';
DROP TABLE IF EXISTS basket_lines;

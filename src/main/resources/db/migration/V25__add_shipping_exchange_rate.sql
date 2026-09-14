-- Shipping cost used to convert at the same rate as item prices (just with a hidden markup on
-- top). Give it its own admin-editable rate instead, seeded from the current rate so nothing
-- changes until an admin deliberately sets it.
ALTER TABLE exchange_rate_setting ADD COLUMN shipping_jpy_per_eur NUMERIC(10, 4);
UPDATE exchange_rate_setting SET shipping_jpy_per_eur = jpy_per_eur;
ALTER TABLE exchange_rate_setting ALTER COLUMN shipping_jpy_per_eur SET NOT NULL;

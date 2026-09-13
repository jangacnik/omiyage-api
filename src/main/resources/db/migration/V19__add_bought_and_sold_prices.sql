ALTER TABLE inventory_items ADD COLUMN bought_price NUMERIC(12, 2);
ALTER TABLE order_items ADD COLUMN sold_price NUMERIC(12, 2);

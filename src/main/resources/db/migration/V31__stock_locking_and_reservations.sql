ALTER TABLE inventory_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE order_items ADD COLUMN reserved_quantity INT NOT NULL DEFAULT 0;
UPDATE order_items SET reserved_quantity = quantity WHERE inventory_item_id IS NOT NULL;

ALTER TABLE inventory_items ADD COLUMN is_preorder boolean NOT NULL DEFAULT false;
ALTER TABLE inventory_items ADD COLUMN expected_release_date date;

ALTER TABLE order_items ADD COLUMN is_preorder boolean NOT NULL DEFAULT false;
ALTER TABLE order_items ADD COLUMN expected_release_date date;

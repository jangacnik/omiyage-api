ALTER TABLE order_items ADD COLUMN status varchar(20) NOT NULL DEFAULT 'REQUESTED';
UPDATE order_items SET status = 'BOUGHT' WHERE purchased = true;
ALTER TABLE order_items DROP COLUMN purchased;

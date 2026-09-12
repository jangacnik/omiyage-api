ALTER TABLE orders ALTER COLUMN status TYPE varchar(20);
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('REQUESTED', 'IN_PROGRESS', 'PURCHASED', 'PREPARING_SHIPPING', 'SENT', 'DELIVERED', 'CANCELLED'));

-- The order whose (possibly pre-existing) shipment absorbed the others when orders were combined -
-- only that user may submit the shipment for shipping.
ALTER TABLE shipments ADD COLUMN owner_user_id uuid;

ALTER TABLE order_items ADD COLUMN paid boolean NOT NULL DEFAULT false;
ALTER TABLE order_items ADD COLUMN paid_at timestamptz;
ALTER TABLE order_items ADD COLUMN link varchar(2048);

ALTER TABLE inventory_items ADD COLUMN link varchar(2048);

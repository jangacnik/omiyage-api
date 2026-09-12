ALTER TABLE orders ALTER COLUMN status TYPE varchar(20);
ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('REQUESTED', 'IN_PROGRESS', 'PURCHASED', 'PREPARING_SHIPPING', 'SENT', 'DELIVERED', 'CANCELLED'));

-- The order whose (possibly pre-existing) shipment absorbed the others when orders were combined -
-- only that user may submit the shipment for shipping.
ALTER TABLE shipments ADD COLUMN owner_user_id uuid;

-- Backfill existing shipments so submit-for-shipping has someone to authorize against - a null
-- owner is treated as "nobody may submit this", not as "fall back to whoever's order it is", so
-- every pre-existing shipment needs an owner assigned now rather than staying null indefinitely.
-- Picks the earliest-attached order's user as a reasonable default; an admin can reassign orders
-- (which re-derives ownership) if that guess is wrong for a given shipment.
UPDATE shipments s
SET owner_user_id = (
    SELECT o.user_id FROM orders o WHERE o.shipment_id = s.id ORDER BY o.created_at ASC LIMIT 1
)
WHERE s.owner_user_id IS NULL;

ALTER TABLE order_items ADD COLUMN paid boolean NOT NULL DEFAULT false;
ALTER TABLE order_items ADD COLUMN paid_at timestamptz;
ALTER TABLE order_items ADD COLUMN link varchar(2048);

ALTER TABLE inventory_items ADD COLUMN link varchar(2048);

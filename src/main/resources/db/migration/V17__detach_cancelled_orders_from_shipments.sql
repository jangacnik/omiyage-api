-- Cancelling an order used to leave it attached to its shipment, still counting toward that
-- shipment's weight and billed a shipping-cost share (fixed in application code alongside this
-- migration). Clean up any orders already left in that state before the fix landed.
UPDATE orders
SET shipment_id = NULL
WHERE status = 'CANCELLED' AND shipment_id IS NOT NULL AND deleted_at IS NULL;

-- Soft-delete (not a hard DELETE) to match the app's @SQLDelete convention for shipments.
UPDATE shipments
SET deleted_at = now()
WHERE deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM orders WHERE orders.shipment_id = shipments.id AND orders.deleted_at IS NULL);

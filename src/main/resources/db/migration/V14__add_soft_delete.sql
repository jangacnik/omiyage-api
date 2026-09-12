ALTER TABLE order_items ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE shipments ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE inventory_items ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE categories ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_order_items_deleted_at ON order_items (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_orders_deleted_at ON orders (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_shipments_deleted_at ON shipments (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_inventory_items_deleted_at ON inventory_items (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_categories_deleted_at ON categories (deleted_at) WHERE deleted_at IS NOT NULL;

-- Soft-deleted categories keep their row (for the 90-day tracking window) but must free up
-- their name/slug so a new active category can reuse it immediately.
ALTER TABLE categories DROP CONSTRAINT categories_name_key;
ALTER TABLE categories DROP CONSTRAINT categories_slug_key;
CREATE UNIQUE INDEX uq_categories_name_active ON categories (name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_categories_slug_active ON categories (slug) WHERE deleted_at IS NULL;

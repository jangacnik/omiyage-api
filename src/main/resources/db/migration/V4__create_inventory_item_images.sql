CREATE TABLE inventory_item_images (
    id UUID PRIMARY KEY,
    inventory_item_id UUID NOT NULL REFERENCES inventory_items (id) ON DELETE CASCADE,
    object_key VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_inventory_item_images_item ON inventory_item_images (inventory_item_id);

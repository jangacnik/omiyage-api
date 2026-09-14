-- Freeform admin-entered labels, distinct from the curated Category taxonomy - no separate
-- management page, just short strings attached directly to the item.
CREATE TABLE inventory_item_tags (
    inventory_item_id UUID NOT NULL REFERENCES inventory_items(id) ON DELETE CASCADE,
    tag VARCHAR(50) NOT NULL,
    PRIMARY KEY (inventory_item_id, tag)
);

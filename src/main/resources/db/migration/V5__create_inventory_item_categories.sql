CREATE TABLE inventory_item_categories (
    inventory_item_id UUID NOT NULL REFERENCES inventory_items (id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (inventory_item_id, category_id)
);

CREATE INDEX idx_inventory_item_categories_category ON inventory_item_categories (category_id);

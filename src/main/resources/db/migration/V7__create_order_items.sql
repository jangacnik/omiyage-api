CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    inventory_item_id UUID REFERENCES inventory_items (id) ON DELETE SET NULL,
    custom_title VARCHAR(200),
    custom_description TEXT,
    custom_image_key VARCHAR(500),
    condition VARCHAR(10) CHECK (condition IN ('NEW', 'USED')),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('JPY', 'EUR')),
    max_price NUMERIC(12, 2),
    actual_price NUMERIC(12, 2),
    purchased BOOLEAN NOT NULL DEFAULT false,
    purchased_at TIMESTAMPTZ,
    quantity INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_items_source_check CHECK (inventory_item_id IS NOT NULL OR custom_title IS NOT NULL)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

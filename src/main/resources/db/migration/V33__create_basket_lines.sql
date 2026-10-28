CREATE TABLE basket_lines (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    inventory_item_id UUID REFERENCES inventory_items (id) ON DELETE CASCADE,
    custom_title VARCHAR(200),
    custom_description TEXT,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('JPY', 'EUR')),
    max_price NUMERIC(12, 2),
    quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT basket_lines_source_check CHECK (inventory_item_id IS NOT NULL OR custom_title IS NOT NULL)
);

CREATE INDEX idx_basket_lines_user ON basket_lines (user_id);

CREATE TABLE inventory_items (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    condition VARCHAR(10) NOT NULL CHECK (condition IN ('NEW', 'USED')),
    price_amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('JPY', 'EUR')),
    stock_quantity INT NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_items_title ON inventory_items (title);
CREATE INDEX idx_inventory_items_status ON inventory_items (status);

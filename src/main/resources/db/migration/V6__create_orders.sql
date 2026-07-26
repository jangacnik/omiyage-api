CREATE TABLE orders (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_profiles (id),
    status VARCHAR(15) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'IN_PROGRESS', 'PURCHASED', 'SENT', 'DELIVERED', 'CANCELLED')),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_user ON orders (user_id);
CREATE INDEX idx_orders_status ON orders (status);

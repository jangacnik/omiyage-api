CREATE TABLE user_profiles (
    id UUID PRIMARY KEY,
    nickname VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    phone VARCHAR(64),
    shipping_address TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

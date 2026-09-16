CREATE TABLE item_templates (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    title VARCHAR(255),
    description TEXT,
    price_amount NUMERIC,
    bought_price NUMERIC,
    weight NUMERIC
);

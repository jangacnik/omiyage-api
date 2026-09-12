-- Single admin-editable JPY-per-1-EUR rate, used to display shipping costs (and, on the frontend,
-- item prices) in the viewer's preferred currency. Seeded with a placeholder - the admin must set
-- a real value via PUT /api/admin/exchange-rate before EUR display numbers are meaningful.
CREATE TABLE exchange_rate_setting (
    id SMALLINT PRIMARY KEY,
    jpy_per_eur NUMERIC(10, 4) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO exchange_rate_setting (id, jpy_per_eur, updated_at) VALUES (1, 160.0000, now());

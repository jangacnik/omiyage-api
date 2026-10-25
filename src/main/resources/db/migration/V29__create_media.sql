CREATE TABLE media (
    object_key VARCHAR(255) PRIMARY KEY,
    content_type VARCHAR(64) NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

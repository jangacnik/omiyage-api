CREATE TABLE shipments (
    id uuid PRIMARY KEY,
    status varchar(20) NOT NULL DEFAULT 'PREPARING',
    tracking_number varchar,
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE orders ADD COLUMN shipment_id uuid REFERENCES shipments(id);

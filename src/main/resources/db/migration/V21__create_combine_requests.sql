CREATE TABLE combine_requests (
    id uuid PRIMARY KEY,
    requester_order_id uuid NOT NULL REFERENCES orders(id),
    target_order_id uuid NOT NULL REFERENCES orders(id),
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    resulting_shipment_id uuid REFERENCES shipments(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    responded_at timestamptz
);

CREATE UNIQUE INDEX combine_requests_pending_pair_uq
    ON combine_requests (requester_order_id, target_order_id)
    WHERE status = 'PENDING';

DROP INDEX combine_requests_pending_pair_uq;

ALTER TABLE combine_requests DROP COLUMN target_order_id;
ALTER TABLE combine_requests ADD COLUMN target_shipment_id uuid NOT NULL REFERENCES shipments(id);

CREATE UNIQUE INDEX combine_requests_pending_pair_uq
    ON combine_requests (requester_order_id, target_shipment_id)
    WHERE status = 'PENDING';

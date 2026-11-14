ALTER TABLE shipments
    MODIFY COLUMN auto_confirm_at DATETIME NULL COMMENT '签收后自动确认收货的截止时间',
    ADD COLUMN simulated_in_transit_at DATETIME NULL COMMENT '演示物流的运输节点时间',
    ADD COLUMN carrier_signed_at DATETIME NULL COMMENT '物流签收时间';

UPDATE shipments SET auto_confirm_at = NULL WHERE status = 'SHIPPED';

UPDATE outbox_events
SET payload = JSON_SET(payload, '$.retired', TRUE),
    status = CASE WHEN status = 'PENDING' THEN 'DEAD' ELSE status END,
    claim_token = NULL,
    claim_until = NULL
WHERE event_type = 'SHIPMENT_AUTO_CONFIRM';

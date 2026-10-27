ALTER TABLE orders
    ADD COLUMN expiry_claim_token VARCHAR(36) NULL COMMENT '到期扫描认领标识',
    ADD COLUMN expiry_claim_until DATETIME(3) NULL COMMENT '到期扫描租约截止',
    ALGORITHM=INSTANT;

ALTER TABLE refund_attempts
    ADD COLUMN reconcile_claim_token VARCHAR(36) NULL COMMENT '退款核对认领标识',
    ADD COLUMN reconcile_claim_until DATETIME(3) NULL COMMENT '退款核对租约截止',
    ALGORITHM=INSTANT;

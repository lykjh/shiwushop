ALTER TABLE outbox_events
    ADD COLUMN claim_token VARCHAR(36) NULL COMMENT '当前发布者的认领标识',
    ADD COLUMN claim_until DATETIME NULL COMMENT '认领到期时间';

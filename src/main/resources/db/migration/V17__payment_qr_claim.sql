ALTER TABLE payment_attempts
    ADD COLUMN qr_claim_token VARCHAR(36) NULL COMMENT '二维码生成租约令牌',
    ADD COLUMN qr_claim_expires_at DATETIME(3) NULL COMMENT '二维码生成租约到期时间';

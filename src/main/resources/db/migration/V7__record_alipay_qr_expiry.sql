ALTER TABLE payment_attempts
    ADD COLUMN qr_expires_at DATETIME NULL COMMENT '已生成二维码的最晚有效时间上界';

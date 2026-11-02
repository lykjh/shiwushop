ALTER TABLE payment_attempts
    ADD COLUMN qr_code VARCHAR(1024) NULL COMMENT '已签发的支付宝预下单二维码码串';

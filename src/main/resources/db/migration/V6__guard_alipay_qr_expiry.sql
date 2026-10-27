ALTER TABLE payment_attempts
    ADD COLUMN qr_timeout_guaranteed TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '二维码最晚有效期已由预下单参数限制到本地订单截止时间；旧支付尝试为0';

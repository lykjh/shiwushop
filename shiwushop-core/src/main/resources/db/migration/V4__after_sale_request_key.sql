ALTER TABLE after_sale_cases
    ADD COLUMN request_key VARCHAR(64) NULL COMMENT '买家申请幂等键，同订单内唯一' AFTER order_id,
    ADD UNIQUE KEY uk_after_sale_cases_order_request_key (order_id, request_key);

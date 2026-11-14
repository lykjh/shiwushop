ALTER TABLE orders DROP CHECK ck_orders_1,
  MODIFY pay_expires_at DATETIME NULL COMMENT '现金订单付款截止时间',
  ADD CONSTRAINT ck_orders_1 CHECK (
    (settlement_kind='CASH' AND total_amount>0 AND pay_expires_at IS NOT NULL)
    OR (settlement_kind='ZERO_CASH' AND total_amount=0 AND pay_expires_at IS NULL AND paid_at IS NOT NULL)),
  ADD CONSTRAINT ck_orders_price_snapshot CHECK (pricing_version=0 OR
    (pricing_version=1 AND original_amount IS NOT NULL AND original_amount>=0 AND discount_amount>=0
    AND original_amount-discount_amount=total_amount AND platform_subsidy_amount BETWEEN 0 AND discount_amount));

ALTER TABLE order_items ADD CONSTRAINT ck_order_items_cash_snapshot CHECK (
  payable_amount IS NULL OR (item_kind='SALE' AND original_unit_price IS NOT NULL AND original_unit_price>0
  AND discount_amount>=0 AND payable_amount>=0 AND original_unit_price*quantity-discount_amount=payable_amount));

ALTER TABLE after_sale_cases DROP CHECK ck_after_sale_cases_3,
  ADD CONSTRAINT ck_after_sale_cases_3 CHECK (refund_amount>0 OR (refund_amount=0 AND case_origin='ZERO_CASH'));

ALTER TABLE after_sale_items DROP CHECK ck_after_sale_items_2,
  ADD CONSTRAINT ck_after_sale_items_2 CHECK (refund_amount>0 OR (refund_amount=0 AND unit_allocation IS NOT NULL));

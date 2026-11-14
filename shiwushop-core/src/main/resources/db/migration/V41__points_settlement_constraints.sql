-- POINTS keeps zero cash amounts; redeem cost remains in the points ledger.
ALTER TABLE orders DROP CHECK ck_orders_1,
  ADD CONSTRAINT ck_orders_1 CHECK (
    (settlement_kind='CASH' AND total_amount>0 AND pay_expires_at IS NOT NULL)
    OR (settlement_kind='ZERO_CASH' AND total_amount=0 AND pay_expires_at IS NULL AND paid_at IS NOT NULL)
    OR (settlement_kind='POINTS' AND total_amount=0 AND pay_expires_at IS NULL AND paid_at IS NOT NULL));

ALTER TABLE order_items DROP CHECK ck_order_items_2,
  DROP CHECK ck_order_items_cash_snapshot,
  ADD CONSTRAINT ck_order_items_2 CHECK (
    (item_kind='REDEEM' AND unit_price=0)
    OR (item_kind<>'REDEEM' AND unit_price>0)),
  ADD CONSTRAINT ck_order_items_cash_snapshot CHECK (
    (item_kind='REDEEM' AND original_unit_price IS NOT NULL AND original_unit_price>0
      AND unit_price=0 AND discount_amount=0 AND payable_amount=0)
    OR (item_kind<>'REDEEM' AND payable_amount IS NULL)
    OR (item_kind='SALE' AND original_unit_price IS NOT NULL AND original_unit_price>0
      AND discount_amount>=0 AND payable_amount>=0
      AND original_unit_price*quantity-discount_amount=payable_amount));

ALTER TABLE after_sale_cases DROP CHECK ck_after_sale_cases_3,
  ADD CONSTRAINT ck_after_sale_cases_3 CHECK (
    refund_amount>0 OR (refund_amount=0 AND case_origin IN ('ZERO_CASH','POINTS')));

-- Keep existing keys and the unique index; use the activity request key's case-sensitive identity.
ALTER TABLE checkout_requests
  MODIFY idempotency_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL
  COMMENT '客户端结算幂等键';

ALTER TABLE orders
  ADD business_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '下单业务类型',
  ADD settlement_kind VARCHAR(16) NOT NULL DEFAULT 'CASH' COMMENT '结算来源',
  ADD fulfillment_state VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT '履约门禁',
  ADD member_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '下单会员等级快照',
  ADD original_amount DECIMAL(18,2) NULL COMMENT '原普通价合计',
  ADD discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '现金优惠总额',
  ADD platform_subsidy_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '平台承担现金优惠',
  ADD pricing_version INT NOT NULL DEFAULT 0 COMMENT '定价版本',
  ADD CONSTRAINT fk_orders_member_level FOREIGN KEY (member_level_code) REFERENCES membership_levels(level_code);

ALTER TABLE order_items
  ADD item_kind VARCHAR(16) NOT NULL DEFAULT 'SALE' COMMENT '明细种类',
  ADD original_unit_price DECIMAL(18,2) NULL COMMENT '原普通价单价',
  ADD discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '分摊优惠总额',
  ADD payable_amount DECIMAL(18,2) NULL COMMENT '最终实付金额',
  ADD source_activity_id BIGINT NULL COMMENT '来源活动版本',
  ADD CONSTRAINT fk_order_items_source_activity FOREIGN KEY (source_activity_id) REFERENCES marketing_activities(id);

ALTER TABLE after_sale_cases
  ADD case_origin VARCHAR(24) NOT NULL DEFAULT 'CUSTOMER' COMMENT '售后来源',
  ADD points_return_units BIGINT NOT NULL DEFAULT 0 COMMENT '本案退回积分单位';

ALTER TABLE after_sale_items
  ADD points_return_units BIGINT NOT NULL DEFAULT 0 COMMENT '本明细退回积分单位',
  ADD unit_allocation JSON NULL COMMENT '占用的原商品单位区间及其退款额';

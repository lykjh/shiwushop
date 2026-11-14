ALTER TABLE shipments
  ADD COLUMN request_key VARCHAR(64) NULL COMMENT '订单内发货幂等键',
  ADD COLUMN allocation_state VARCHAR(16) NOT NULL DEFAULT 'COMPLETE' COMMENT '旧运单件数映射状态',
  ADD KEY ix_shipments_order_id (order_id, id),
  ADD UNIQUE KEY uk_shipments_order_request (order_id, request_key),
  ADD CONSTRAINT ck_shipments_allocation_state CHECK (allocation_state IN ('COMPLETE','NEEDS_REVIEW')),
  DROP INDEX uk_shipments_order_id;

CREATE TABLE shipment_items (
  shipment_id BIGINT NOT NULL COMMENT '包裹ID',
  order_item_id BIGINT NOT NULL COMMENT '订单明细ID',
  quantity INT NOT NULL COMMENT '本包发货件数',
  PRIMARY KEY (shipment_id, order_item_id),
  KEY ix_shipment_items_order_item (order_item_id, shipment_id),
  CONSTRAINT fk_shipment_items_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id) ON DELETE RESTRICT,
  CONSTRAINT fk_shipment_items_order_item FOREIGN KEY (order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
  CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='包裹内的订单商品件数';

ALTER TABLE orders ADD COLUMN fully_received_at DATETIME NULL COMMENT '全部仍需履约件数收齐时刻';

ALTER TABLE after_sale_items
  ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'LEGACY_UNKNOWN' COMMENT '退款件数原履约来源',
  ADD COLUMN source_shipment_id BIGINT NULL COMMENT '已发货件数所属包裹',
  ADD KEY ix_after_sale_items_source_shipment (source_shipment_id),
  ADD CONSTRAINT fk_after_sale_items_source_shipment FOREIGN KEY (source_shipment_id)
    REFERENCES shipments(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_after_sale_items_source CHECK (
    (source_kind='SHIPMENT' AND source_shipment_id IS NOT NULL)
    OR (source_kind IN ('UNSHIPPED','LEGACY_UNKNOWN') AND source_shipment_id IS NULL));

-- 旧版一单一运单；同秒发生的退款与发货无法由DATETIME判定先后，留待人工核实。
UPDATE shipments s SET allocation_state='NEEDS_REVIEW'
WHERE EXISTS (
  SELECT 1 FROM after_sale_cases c
  WHERE c.order_id=s.order_id AND c.status='REFUNDED'
    AND (c.finished_at IS NULL OR c.finished_at=s.shipped_at));

UPDATE after_sale_items ai
JOIN after_sale_cases c ON c.id=ai.case_id
LEFT JOIN shipments s ON s.order_id=c.order_id
SET ai.source_kind=CASE
      WHEN s.id IS NULL OR (c.status='REFUNDED' AND c.finished_at<s.shipped_at) THEN 'UNSHIPPED'
      WHEN c.requested_at>s.shipped_at THEN 'SHIPMENT'
      ELSE 'LEGACY_UNKNOWN' END,
    ai.source_shipment_id=CASE WHEN s.id IS NOT NULL AND c.requested_at>s.shipped_at
      THEN s.id ELSE NULL END;

INSERT INTO shipment_items (shipment_id, order_item_id, quantity)
SELECT s.id, oi.id, oi.quantity-COALESCE(prior.refunded_quantity,0)
FROM shipments s
JOIN order_items oi ON oi.order_id=s.order_id
LEFT JOIN (
  SELECT historical.id AS shipment_id, ai.order_item_id, SUM(ai.quantity) AS refunded_quantity
  FROM shipments historical
  JOIN after_sale_cases c ON c.order_id=historical.order_id
    AND c.status='REFUNDED' AND c.finished_at<historical.shipped_at
  JOIN after_sale_items ai ON ai.case_id=c.id
  GROUP BY historical.id, ai.order_item_id
) prior ON prior.shipment_id=s.id AND prior.order_item_id=oi.id
WHERE s.allocation_state='COMPLETE' AND oi.quantity>COALESCE(prior.refunded_quantity,0);

UPDATE shipments s SET allocation_state='NEEDS_REVIEW'
WHERE NOT EXISTS (SELECT 1 FROM shipment_items si WHERE si.shipment_id=s.id);

UPDATE orders o
JOIN shipments s ON s.order_id=o.id AND s.status='CONFIRMED' AND s.allocation_state='COMPLETE'
SET o.fully_received_at=s.confirmed_at
WHERE EXISTS (SELECT 1 FROM shipment_items si WHERE si.shipment_id=s.id);

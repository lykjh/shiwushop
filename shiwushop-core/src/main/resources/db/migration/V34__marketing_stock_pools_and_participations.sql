-- MK-03 marketing-owned tables; normal inventory reason CHECK is in V33.
CREATE TABLE marketing_stock_pools (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动库存池ID',
 activity_sku_id BIGINT NOT NULL COMMENT '所属活动SKU范围',
 allocated_qty INT NOT NULL DEFAULT 0 COMMENT '累计从普通库存划入',
 available_qty INT NOT NULL DEFAULT 0 COMMENT '活动可售数量',
 held_qty INT NOT NULL DEFAULT 0 COMMENT '活动待成交占用',
 consumed_qty INT NOT NULL DEFAULT 0 COMMENT '活动已售净数量',
 returned_to_normal_qty INT NOT NULL DEFAULT 0 COMMENT '累计划回普通库存',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '库存池版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_stock_pool_scope(activity_sku_id),
 CONSTRAINT fk_marketing_stock_pool_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_stock_pool_balance CHECK (
  allocated_qty>=0 AND available_qty>=0 AND held_qty>=0 AND consumed_qty>=0
  AND returned_to_normal_qty>=0 AND version>=0
  AND allocated_qty=available_qty+held_qty+consumed_qty+returned_to_normal_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='从普通库存划拨的活动专用库存';

CREATE TABLE marketing_participations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动参与ID',
 activity_sku_id BIGINT NOT NULL COMMENT '活动SKU范围',
 user_id BIGINT NOT NULL COMMENT '参与账号',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次参与请求键',
 request_hash BINARY(32) NOT NULL COMMENT '冻结请求SHA256摘要',
 order_id BIGINT NULL COMMENT '成功转化订单',
 quantity INT NOT NULL COMMENT '参与件数',
 status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '参与状态',
 expires_at DATETIME(3) NOT NULL COMMENT '参与绝对截止时刻',
 claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '到期任务租约令牌',
 claim_until DATETIME(3) NULL COMMENT '到期任务租约截止',
 paid_at DATETIME(3) NULL COMMENT '已确认付款时刻',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_participation_request(user_id,request_key),
 UNIQUE KEY uk_marketing_participation_order(order_id),
 KEY ix_marketing_participation_due(status,expires_at,id),
 KEY ix_marketing_participation_actor(activity_sku_id,user_id,id),
 CONSTRAINT fk_marketing_participation_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_participation_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_participation_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_participation_request CHECK (request_key REGEXP '^[!-~]{1,64}$'),
 CONSTRAINT ck_marketing_participation_quantity CHECK (quantity>0),
 CONSTRAINT ck_marketing_participation_status CHECK (status IN ('RESERVED','PAID','QUALIFIED','RELEASED','CLOSED','REFUND_PENDING','REFUNDED')),
 CONSTRAINT ck_marketing_participation_claim CHECK ((claim_token IS NULL)=(claim_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动库存与限额参与义务';

CREATE TABLE marketing_stock_entries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动库存流水ID',
 pool_id BIGINT NOT NULL COMMENT '活动库存池',
 operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等业务键',
 reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '机器库存事件类型',
 allocated_delta INT NOT NULL DEFAULT 0 COMMENT '累计划拨变化',
 available_delta INT NOT NULL DEFAULT 0 COMMENT '活动可售变化',
 held_delta INT NOT NULL DEFAULT 0 COMMENT '活动占用变化',
 consumed_delta INT NOT NULL DEFAULT 0 COMMENT '活动已售变化',
 returned_delta INT NOT NULL DEFAULT 0 COMMENT '划回普通库存变化',
 order_item_id BIGINT NULL COMMENT '关联订单项',
 participation_id BIGINT NULL COMMENT '关联活动参与',
 normal_movement_id BIGINT NULL COMMENT '对应普通库存流水',
 actor_user_id BIGINT NULL COMMENT '人工划拨操作者',
 operation_reason VARCHAR(500) NULL COMMENT '完整人工划拨原因',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_marketing_stock_operation(operation_key),
 KEY ix_marketing_stock_pool_entry(pool_id,id),
 KEY ix_marketing_stock_order_item(order_item_id,id),
 CONSTRAINT fk_marketing_stock_entry_pool FOREIGN KEY(pool_id) REFERENCES marketing_stock_pools(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_stock_entry_item FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_stock_entry_participation FOREIGN KEY(participation_id) REFERENCES marketing_participations(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_stock_entry_normal FOREIGN KEY(normal_movement_id) REFERENCES inventory_movements(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_stock_entry_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_stock_entry_balance CHECK (
  allocated_delta=available_delta+held_delta+consumed_delta+returned_delta),
 CONSTRAINT ck_marketing_stock_entry_manual CHECK (
  (actor_user_id IS NULL AND operation_reason IS NULL)
  OR (actor_user_id IS NOT NULL AND operation_reason IS NOT NULL AND CHAR_LENGTH(operation_reason) BETWEEN 1 AND 500))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动库存流转与普通库存对应流水';




-- MK-06 point redemption facts.
CREATE TABLE point_redemptions (
 order_id BIGINT PRIMARY KEY COMMENT '零现金兑换订单ID',
 participation_id BIGINT NOT NULL COMMENT '活动库存参与ID',
 activity_sku_id BIGINT NOT NULL COMMENT '兑换活动SKU范围',
 user_id BIGINT NOT NULL COMMENT '兑换账号',
 spent_point_units BIGINT NOT NULL COMMENT '原始扣除积分最小单位',
 returned_point_units BIGINT NOT NULL DEFAULT 0 COMMENT '已确认返还积分最小单位',
 source_entry_id BIGINT NOT NULL COMMENT '积分扣减账本流水',
 status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '兑换与返还状态',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '兑换请求幂等键',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_point_redemption_participation(participation_id),
 UNIQUE KEY uk_point_redemption_request(user_id,request_key),
 KEY ix_point_redemption_user(user_id,order_id),
 CONSTRAINT fk_point_redemption_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT fk_point_redemption_participation FOREIGN KEY(participation_id) REFERENCES marketing_participations(id) ON DELETE RESTRICT,
 CONSTRAINT fk_point_redemption_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT fk_point_redemption_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_point_redemption_source FOREIGN KEY(source_entry_id) REFERENCES member_point_entries(id) ON DELETE RESTRICT,
 CONSTRAINT ck_point_redemption_balance CHECK (spent_point_units>0 AND returned_point_units>=0 AND returned_point_units<=spent_point_units),
 CONSTRAINT ck_point_redemption_status CHECK (status IN ('CREATED','FULFILLING','COMPLETED','CANCELLED','RETURNING','RETURNED')),
 CONSTRAINT ck_point_redemption_request CHECK (request_key REGEXP '^[!-~]{1,64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='纯积分兑换与原来源返还';


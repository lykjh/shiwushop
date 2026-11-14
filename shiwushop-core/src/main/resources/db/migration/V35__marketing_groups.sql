-- MK-04 group lifecycle and participation link.
CREATE TABLE marketing_groups (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '团ID',
 activity_sku_id BIGINT NOT NULL COMMENT '拼团活动SKU范围',
 leader_user_id BIGINT NOT NULL COMMENT '当前团长账号',
 required_members INT NOT NULL COMMENT '成团人数',
 reserved_members INT NOT NULL DEFAULT 0 COMMENT '未支付占用名额',
 paid_members INT NOT NULL DEFAULT 0 COMMENT '已确认支付人数',
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '团生命周期状态',
 opened_at DATETIME(3) NULL COMMENT '团长支付成功开团时刻',
 expires_at DATETIME(3) NOT NULL COMMENT '团绝对截止时刻',
 succeeded_at DATETIME(3) NULL COMMENT '成团时刻',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '状态版本',
 claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '截止任务租约令牌',
 claim_until DATETIME(3) NULL COMMENT '截止任务租约截止',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 KEY ix_marketing_group_due(status,expires_at,id),
 KEY ix_marketing_group_leader(leader_user_id,id),
 CONSTRAINT fk_marketing_group_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_group_leader FOREIGN KEY(leader_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_group_status CHECK (status IN ('FORMING','SUCCEEDED','FAILED','CLOSED')),
 CONSTRAINT ck_marketing_group_capacity CHECK (required_members BETWEEN 2 AND 100 AND reserved_members>=0 AND paid_members>=0 AND reserved_members+paid_members<=required_members AND version>=0),
 CONSTRAINT ck_marketing_group_claim CHECK ((claim_token IS NULL)=(claim_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拼团成团与截止责任';

ALTER TABLE marketing_participations
 ADD group_id BIGINT NULL COMMENT '所属拼团',
 ADD UNIQUE KEY uk_marketing_participation_group_user(group_id,user_id),
 ADD KEY ix_marketing_participation_group(group_id,status,id),
 ADD CONSTRAINT fk_marketing_participation_group FOREIGN KEY(group_id) REFERENCES marketing_groups(id) ON DELETE RESTRICT;


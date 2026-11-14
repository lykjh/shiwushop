CREATE TABLE membership_levels (
 level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT '会员等级码',
 level_name VARCHAR(32) NOT NULL COMMENT '等级名称',
 rank_no SMALLINT NOT NULL COMMENT '等级顺序',
 min_growth_amount DECIMAL(18,2) NOT NULL COMMENT '最低累计成长金额',
 monthly_coupon_count INT NOT NULL DEFAULT 0 COMMENT '每月权益张数',
 coupon_face_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '月券面额',
 coupon_threshold_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '月券门槛',
 enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_membership_level_rank(rank_no), UNIQUE KEY uk_membership_level_growth(min_growth_amount),
 CONSTRAINT ck_membership_level CHECK (rank_no>=0 AND min_growth_amount>=0 AND monthly_coupon_count>=0 AND coupon_face_amount>=0 AND coupon_threshold_amount>=coupon_face_amount AND enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级与月度权益规则';
INSERT INTO membership_levels(level_code,level_name,rank_no,min_growth_amount,monthly_coupon_count,coupon_face_amount,coupon_threshold_amount) VALUES
 ('ORDINARY','普通',0,0,0,0,0),('SILVER','白银',1,1000,1,5,199),('GOLD','黄金',2,5000,2,10,299),('PLATINUM','白金',3,15000,2,20,499);

CREATE TABLE member_accounts (
 user_id BIGINT PRIMARY KEY COMMENT '用户ID',
 level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '当前等级',
 growth_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '累计净成长金额',
 available_point_units BIGINT NOT NULL DEFAULT 0 COMMENT '可用积分百分之一单位',
 held_point_units BIGINT NOT NULL DEFAULT 0 COMMENT '占用积分单位',
 point_debt_units BIGINT NOT NULL DEFAULT 0 COMMENT '待抵扣积分单位',
 joined_at DATETIME(3) NOT NULL COMMENT '会员资格起始时间',
 reward_start_at DATETIME(3) NOT NULL COMMENT '订单权益起算边界',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '账户版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 KEY ix_member_account_level(level_code,user_id),
 CONSTRAINT fk_member_account_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_account_level FOREIGN KEY(level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT ck_member_account_balance CHECK(growth_amount>=0 AND available_point_units>=0 AND held_point_units>=0 AND point_debt_units>=0 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员成长与积分账户';

CREATE TABLE member_growth_entries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '成长流水ID', user_id BIGINT NOT NULL COMMENT '用户ID',
 order_item_id BIGINT NULL COMMENT '来源订单项', after_sale_case_id BIGINT NULL COMMENT '关联售后',
 source_entry_id BIGINT NULL COMMENT '被冲回或调整的原入账流水',
 event_kind VARCHAR(24) NOT NULL COMMENT '成长事件类型',
 operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等业务键',
 delta_amount DECIMAL(18,2) NOT NULL COMMENT '成长变动', balance_after DECIMAL(18,2) NOT NULL COMMENT '变动后余额',
 old_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原等级',
 new_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '新等级',
 actor_user_id BIGINT NULL COMMENT '人工调整操作人', reason VARCHAR(500) NULL COMMENT '调整理由',
 evidence_reference VARCHAR(128) NULL COMMENT '人工调整凭据引用',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_member_growth_operation(operation_key), KEY ix_member_growth_user(user_id,id),
 KEY ix_member_growth_item(order_item_id,id), KEY ix_member_growth_source(source_entry_id,id),
 CONSTRAINT fk_member_growth_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_item FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_case FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_cases(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_source FOREIGN KEY(source_entry_id) REFERENCES member_growth_entries(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_old FOREIGN KEY(old_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_new FOREIGN KEY(new_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT fk_member_growth_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_member_growth CHECK(event_kind IN ('EARN','REFUND','ADJUST') AND delta_amount<>0 AND balance_after>=0),
 CONSTRAINT ck_member_growth_adjust CHECK(event_kind<>'ADJUST' OR (actor_user_id IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason))>0 AND evidence_reference IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_reference))>0)),
 CONSTRAINT ck_member_growth_reversal CHECK(delta_amount>0 OR source_entry_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成长追加账本及来源冲回';

CREATE TABLE member_level_changes (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '等级历史ID', user_id BIGINT NOT NULL COMMENT '用户ID',
 from_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '变更前等级',
 to_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '变更后等级',
 effective_at DATETIME(3) NOT NULL COMMENT '资格生效时间',
 operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等业务键',
 growth_entry_id BIGINT NULL COMMENT '对应成长流水',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_member_level_operation(operation_key), KEY ix_member_level_history(user_id,effective_at,id),
 CONSTRAINT fk_member_level_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_level_from FOREIGN KEY(from_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT fk_member_level_to FOREIGN KEY(to_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT fk_member_level_entry FOREIGN KEY(growth_entry_id) REFERENCES member_growth_entries(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员等级历史及月初资格来源';

CREATE TABLE member_reward_tasks (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '权益任务ID', user_id BIGINT NOT NULL COMMENT '用户ID',
 order_item_id BIGINT NOT NULL COMMENT '订单项', reward_kind VARCHAR(8) NOT NULL COMMENT '积分或成长',
 exclusion_reason VARCHAR(32) NULL COMMENT '权益排除原因', eligible_at DATETIME(3) NULL COMMENT '权益可入账时间',
 next_attempt_at DATETIME(3) NOT NULL COMMENT '下次处理时间', status VARCHAR(24) NOT NULL COMMENT '任务状态',
 credited_growth_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '原成长入账金额',
 credited_point_units BIGINT NOT NULL DEFAULT 0 COMMENT '原积分入账单位', rule_version INT NOT NULL COMMENT '规则版本',
 last_error_code VARCHAR(64) NULL COMMENT '最近错误码', claim_token VARCHAR(36) NULL COMMENT '认领令牌', claim_until DATETIME(3) NULL COMMENT '认领到期',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_member_reward_item_kind(order_item_id,reward_kind),
 KEY ix_member_reward_due(reward_kind,status,next_attempt_at,id), KEY ix_member_reward_user(user_id,reward_kind,status,id),
 CONSTRAINT fk_member_reward_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_reward_item FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
 CONSTRAINT ck_member_reward_kind CHECK((reward_kind='POINTS' AND credited_growth_amount=0 AND status IN ('WAIT_PAYMENT','READY','APPLIED','EXCLUDED','ATTENTION')) OR (reward_kind='GROWTH' AND credited_point_units=0 AND status IN ('WAIT_RECEIPT','WAIT_AFTERSALE','READY','APPLIED','EXCLUDED','ATTENTION'))),
 CONSTRAINT ck_member_reward_amount CHECK(credited_growth_amount>=0 AND credited_point_units>=0 AND rule_version>0),
 CONSTRAINT ck_member_reward_excluded CHECK((status='EXCLUDED' AND exclusion_reason IS NOT NULL AND exclusion_reason IN ('SELF_SHOP_OWNER','LEGACY_ORDER','NON_CASH_ITEM','FULL_REFUND')) OR (status<>'EXCLUDED' AND exclusion_reason IS NULL)),
 CONSTRAINT ck_member_reward_lease CHECK((claim_token IS NULL)=(claim_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按订单项分别处理成长和积分';

CREATE TABLE member_point_lots (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '积分批次ID', user_id BIGINT NOT NULL COMMENT '用户ID',
 source_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源幂等键', order_item_id BIGINT NULL COMMENT '来源订单项',
 issued_units BIGINT NOT NULL COMMENT '原发放积分单位', available_units BIGINT NOT NULL DEFAULT 0 COMMENT '可用积分单位',
 held_units BIGINT NOT NULL DEFAULT 0 COMMENT '占用积分单位', spent_units BIGINT NOT NULL DEFAULT 0 COMMENT '已消费单位',
 expired_units BIGINT NOT NULL DEFAULT 0 COMMENT '已过期单位', reversed_units BIGINT NOT NULL DEFAULT 0 COMMENT '已冲回单位',
 debt_offset_units BIGINT NOT NULL DEFAULT 0 COMMENT '已抵扣欠额单位', earned_at DATETIME(3) NOT NULL COMMENT '首次入账时间',
 expires_at DATETIME(3) NOT NULL COMMENT '原到期时间', status VARCHAR(16) NOT NULL COMMENT '批次状态',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_member_point_source(source_key), KEY ix_member_point_user_expiry(user_id,status,expires_at,id), KEY ix_member_point_expiry(status,expires_at,id),
 CONSTRAINT fk_member_point_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_item FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
 CONSTRAINT ck_member_point_lot CHECK(issued_units>0 AND available_units>=0 AND held_units>=0 AND spent_units>=0 AND expired_units>=0 AND reversed_units>=0 AND debt_offset_units>=0 AND issued_units=available_units+held_units+spent_units+expired_units+reversed_units+debt_offset_units),
 CONSTRAINT ck_member_point_dates CHECK(expires_at>earned_at),
 CONSTRAINT ck_member_point_status CHECK(status IN ('ACTIVE','EXHAUSTED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分原始来源批次';

CREATE TABLE member_point_entries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '积分流水ID', user_id BIGINT NOT NULL COMMENT '用户ID',
 event_kind VARCHAR(24) NOT NULL COMMENT '积分事件类型', operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',
 order_id BIGINT NULL COMMENT '订单ID', after_sale_case_id BIGINT NULL COMMENT '售后ID',
 available_delta BIGINT NOT NULL DEFAULT 0 COMMENT '可用变动', held_delta BIGINT NOT NULL DEFAULT 0 COMMENT '占用变动', debt_delta BIGINT NOT NULL DEFAULT 0 COMMENT '欠额变动',
 available_after BIGINT NOT NULL COMMENT '变动后可用', held_after BIGINT NOT NULL COMMENT '变动后占用', debt_after BIGINT NOT NULL COMMENT '变动后欠额',
 actor_user_id BIGINT NULL COMMENT '人工操作人', reason VARCHAR(500) NULL COMMENT '人工原因', evidence_reference VARCHAR(128) NULL COMMENT '人工凭据引用',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_member_point_operation(operation_key), KEY ix_member_point_entry_user(user_id,id), KEY ix_member_point_entry_order(order_id,id),
 CONSTRAINT fk_member_point_entry_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_entry_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_entry_case FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_cases(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_entry_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_member_point_entry CHECK(event_kind IN ('EARN','HOLD','SPEND','RELEASE','REFUND_REVERSE','EXPIRE','DEBT_OFFSET','ADJUST','COMPENSATE') AND available_after>=0 AND held_after>=0 AND debt_after>=0),
 CONSTRAINT ck_member_point_adjust CHECK(event_kind<>'ADJUST' OR (actor_user_id IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason))>0 AND evidence_reference IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_reference))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分余额及欠额追加流水';

CREATE TABLE member_point_allocations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '积分来源分配ID', entry_id BIGINT NOT NULL COMMENT '积分流水', lot_id BIGINT NOT NULL COMMENT '来源批次',
 original_allocation_id BIGINT NULL COMMENT '原消费或占用分配', allocation_kind VARCHAR(24) NOT NULL COMMENT '来源变动类型', units BIGINT NOT NULL COMMENT '本次分配单位',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_member_point_allocation(entry_id,lot_id,allocation_kind), KEY ix_member_point_lot(lot_id,id), KEY ix_member_point_original(original_allocation_id,id),
 CONSTRAINT fk_member_point_allocation_entry FOREIGN KEY(entry_id) REFERENCES member_point_entries(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_allocation_lot FOREIGN KEY(lot_id) REFERENCES member_point_lots(id) ON DELETE RESTRICT,
 CONSTRAINT fk_member_point_allocation_original FOREIGN KEY(original_allocation_id) REFERENCES member_point_allocations(id) ON DELETE RESTRICT,
 CONSTRAINT ck_member_point_allocation CHECK(units>0 AND allocation_kind IN ('EARN','HOLD','SPEND','RETURN','EXPIRE','REVERSE','DEBT_OFFSET','DEBT_CREATE','DEBT_REPAY','ADJUST','COMPENSATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分每次操作与原批次对应';

ALTER TABLE orders ADD COLUMN shop_owner_user_id_at_order BIGINT NULL COMMENT '下单事务冻结店主ID；历史不回填',
 ADD CONSTRAINT fk_order_owner_snapshot FOREIGN KEY(shop_owner_user_id_at_order) REFERENCES users(id) ON DELETE RESTRICT;

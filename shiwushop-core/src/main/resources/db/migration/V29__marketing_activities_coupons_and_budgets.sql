CREATE TABLE marketing_activities (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动版本ID',
 activity_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '跨版本活动码',
 version_no INT NOT NULL COMMENT '版本序号', name VARCHAR(120) NOT NULL COMMENT '活动名称',
 activity_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '活动类型',
 status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '生命周期状态',
 start_at DATETIME(3) NOT NULL COMMENT '开始时刻含边界', end_at DATETIME(3) NOT NULL COMMENT '结束时刻不含边界',
 eligible_level_codes JSON NOT NULL COMMENT '适用等级码数组',
 creator_user_id BIGINT NOT NULL COMMENT '创建人', reviewer_user_id BIGINT NULL COMMENT '审核人',
 reviewed_at DATETIME(3) NULL COMMENT '审核时间', review_reason VARCHAR(500) NULL COMMENT '审核原因',
 rules_text TEXT NOT NULL COMMENT '用户可见规则', supersedes_id BIGINT NULL COMMENT '被修订的原版本',
 request_scope VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端创建命令作用域',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次创建请求键',
 request_hash BINARY(32) NOT NULL COMMENT '冻结的首次请求SHA256摘要',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_activity_version(activity_code,version_no),
 UNIQUE KEY uk_marketing_activity_request(creator_user_id,request_scope,request_key),
 KEY ix_marketing_activity_start(status,start_at,id), KEY ix_marketing_activity_end(status,end_at,id),
 CONSTRAINT fk_marketing_activity_creator FOREIGN KEY(creator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_activity_reviewer FOREIGN KEY(reviewer_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_activity_previous FOREIGN KEY(supersedes_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_activity_type CHECK(activity_type IN ('MEMBER_PRICE','COUPON','LIMITED_PRICE','FLASH','GROUP','BARGAIN','FULL_REDUCTION','FULL_GIFT','POINTS')),
 CONSTRAINT ck_marketing_activity_status CHECK(status IN ('DRAFT','PENDING_REVIEW','APPROVED','RUNNING','ENDED','REJECTED','PAUSED','TERMINATED')),
 CONSTRAINT ck_marketing_activity_time CHECK(end_at>start_at AND version_no>0),
 CONSTRAINT ck_marketing_activity_review CHECK(reviewer_user_id IS NULL OR reviewer_user_id<>creator_user_id),
 CONSTRAINT ck_marketing_activity_levels CHECK(JSON_TYPE(eligible_level_codes)='ARRAY' AND JSON_LENGTH(eligible_level_codes) BETWEEN 1 AND 4),
 CONSTRAINT ck_marketing_activity_request CHECK(request_key REGEXP '^[!-~]{1,64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动版本与审批';

CREATE TABLE marketing_activity_skus (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动SKU范围ID', activity_id BIGINT NOT NULL COMMENT '活动版本',
 sku_id BIGINT NOT NULL COMMENT '商品SKU', scope_role VARCHAR(12) NOT NULL DEFAULT 'SALE' COMMENT '销售赠品兑换范围',
 activity_price DECIMAL(18,2) NULL COMMENT '活动固定单价', per_user_limit INT NULL COMMENT '每账号数量上限',
 sales_limit INT NULL COMMENT '本项销售数量上限', reserved_qty INT NOT NULL DEFAULT 0 COMMENT '待确认占用量',
 paid_qty INT NOT NULL DEFAULT 0 COMMENT '已付款累计量', team_size SMALLINT NULL COMMENT '成团人数',
 participation_seconds INT NULL COMMENT '单次参与期限秒', bargain_start_price DECIMAL(18,2) NULL COMMENT '砍价起始价',
 bargain_target_price DECIMAL(18,2) NULL COMMENT '砍价目标价', required_helpers INT NULL COMMENT '所需助力人数',
 daily_help_limit INT NULL COMMENT '每日可助力上限', max_open_sessions INT NULL COMMENT '进行中砍价上限',
 point_cost_units BIGINT NULL COMMENT '兑换所需积分最小单位', unit_cost_amount DECIMAL(18,2) NULL COMMENT '单件供货成本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_scope(activity_id,sku_id,scope_role), KEY ix_marketing_scope_sku(sku_id,activity_id),
 CONSTRAINT fk_marketing_scope_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_scope_sku FOREIGN KEY(sku_id) REFERENCES skus(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_scope_role CHECK(scope_role IN ('SALE','GIFT','REDEEM')),
 CONSTRAINT ck_marketing_scope_quantity CHECK(reserved_qty>=0 AND paid_qty>=0 AND (per_user_limit IS NULL OR per_user_limit>0) AND (sales_limit IS NULL OR sales_limit>0)),
 CONSTRAINT ck_marketing_scope_price CHECK((activity_price IS NULL OR activity_price>0) AND (unit_cost_amount IS NULL OR unit_cost_amount>=0)),
 CONSTRAINT ck_marketing_scope_group CHECK((team_size IS NULL OR team_size BETWEEN 2 AND 100) AND (participation_seconds IS NULL OR participation_seconds BETWEEN 60 AND 86400)),
 CONSTRAINT ck_marketing_scope_bargain CHECK((bargain_start_price IS NULL OR bargain_start_price>0) AND (bargain_target_price IS NULL OR bargain_target_price>0) AND (required_helpers IS NULL OR required_helpers BETWEEN 1 AND 100) AND (daily_help_limit IS NULL OR daily_help_limit BETWEEN 1 AND 100) AND (max_open_sessions IS NULL OR max_open_sessions BETWEEN 1 AND 10000)),
 CONSTRAINT ck_marketing_scope_points CHECK(point_cost_units IS NULL OR (point_cost_units>0 AND MOD(point_cost_units,100)=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动商品范围及专属参数';

CREATE TABLE marketing_member_prices (
 activity_sku_id BIGINT NOT NULL COMMENT '活动SKU范围',
 level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '会员等级',
 price DECIMAL(18,2) NOT NULL COMMENT '等级专属单价',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 PRIMARY KEY(activity_sku_id,level_code),
 CONSTRAINT fk_marketing_price_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_price_level FOREIGN KEY(level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_price CHECK(level_code IN ('SILVER','GOLD','PLATINUM') AND price>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动分等级会员价格';

CREATE TABLE marketing_threshold_tiers (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '优惠阶梯ID', activity_id BIGINT NOT NULL COMMENT '活动版本',
 threshold_amount DECIMAL(18,2) NOT NULL COMMENT '满足门槛', reduction_amount DECIMAL(18,2) NULL COMMENT '满减金额',
 gift_activity_sku_id BIGINT NULL COMMENT '赠品活动SKU范围', gift_quantity INT NULL COMMENT '赠品件数',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_marketing_tier(activity_id,threshold_amount),
 CONSTRAINT fk_marketing_tier_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_tier_gift FOREIGN KEY(gift_activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_tier CHECK(threshold_amount>0 AND ((reduction_amount IS NOT NULL AND reduction_amount>0 AND reduction_amount<=threshold_amount AND gift_activity_sku_id IS NULL AND gift_quantity IS NULL) OR (reduction_amount IS NULL AND gift_activity_sku_id IS NOT NULL AND gift_quantity IS NOT NULL AND gift_quantity BETWEEN 1 AND 10000)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='满减及满赠阶梯';

CREATE TABLE marketing_budgets (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '承担方预算ID', activity_id BIGINT NOT NULL COMMENT '活动版本',
 party VARCHAR(12) NOT NULL COMMENT '承担方类型', shop_id BIGINT NULL COMMENT '承担店铺平台为空',
 party_key BIGINT GENERATED ALWAYS AS (CASE WHEN party='PLATFORM' THEN 0 ELSE shop_id END) STORED COMMENT '平台零或商家店铺唯一键',
 budget_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '预算总额', held_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '承诺占用',
 spent_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已确认净支出', platform_share_bp INT NULL COMMENT '对应店铺平台承担万分比',
 agreed_by_user_id BIGINT NULL COMMENT '店铺协议确认人', agreed_at DATETIME(3) NULL COMMENT '协议确认时间',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '预算版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_budget_party(activity_id,party_key), KEY ix_marketing_budget_shop(shop_id,activity_id),
 CONSTRAINT fk_marketing_budget_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_shop FOREIGN KEY(shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_agreed_by FOREIGN KEY(agreed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_budget_party CHECK((party='PLATFORM' AND shop_id IS NULL AND platform_share_bp IS NULL AND agreed_by_user_id IS NULL AND agreed_at IS NULL) OR (party='MERCHANT' AND shop_id IS NOT NULL AND platform_share_bp IS NOT NULL AND platform_share_bp BETWEEN 0 AND 10000)),
 CONSTRAINT ck_marketing_budget_agreement CHECK((agreed_by_user_id IS NULL AND agreed_at IS NULL) OR (agreed_by_user_id IS NOT NULL AND agreed_at IS NOT NULL)),
 CONSTRAINT ck_marketing_budget_balance CHECK(budget_amount>=0 AND held_amount>=0 AND spent_amount>=0 AND held_amount+spent_amount<=budget_amount AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动各承担方预算与协议';

CREATE TABLE marketing_budget_holds (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '预算承诺ID', budget_id BIGINT NOT NULL COMMENT '承担方预算',
 operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本轮稳定业务键',
 source_kind VARCHAR(24) NOT NULL COMMENT '承诺来源类型', source_id BIGINT NOT NULL COMMENT '来源业务ID',
 parent_hold_id BIGINT NULL COMMENT '转移或恢复的原承诺', authorized_amount DECIMAL(18,2) NOT NULL COMMENT '本次承诺上限',
 held_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '当前占用', spent_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '当前净支出',
 released_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '累计释放流量', refunded_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '累计退款冲回流量',
 expires_at DATETIME(3) NULL COMMENT '承诺期限', status VARCHAR(20) NOT NULL COMMENT '承诺状态',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_hold_operation(budget_id,operation_key), KEY ix_marketing_hold_source(source_kind,source_id,id), KEY ix_marketing_hold_expiry(status,expires_at,id),
 CONSTRAINT fk_marketing_hold_budget FOREIGN KEY(budget_id) REFERENCES marketing_budgets(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_hold_parent FOREIGN KEY(parent_hold_id) REFERENCES marketing_budget_holds(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_hold_source CHECK(source_kind IN ('COUPON','ORDER','GROUP','BARGAIN','POINTS') AND source_id>0),
 CONSTRAINT ck_marketing_hold_status CHECK(status IN ('HELD','PARTIAL','SPENT','RELEASED')),
 CONSTRAINT ck_marketing_hold_balance CHECK(authorized_amount>=0 AND held_amount>=0 AND spent_amount>=0 AND released_amount>=0 AND refunded_amount>=0 AND held_amount+spent_amount<=authorized_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算承诺及订单占用轮次';

CREATE TABLE marketing_budget_entries (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '预算流水ID', budget_id BIGINT NOT NULL COMMENT '承担方预算', hold_id BIGINT NULL COMMENT '承诺轮次',
 operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等业务键', event_kind VARCHAR(24) NOT NULL COMMENT '预算事件类型',
 held_delta DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '占用变化', spent_delta DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '净支出变化',
 held_after DECIMAL(18,2) NOT NULL COMMENT '变化后占用', spent_after DECIMAL(18,2) NOT NULL COMMENT '变化后净支出',
 order_id BIGINT NULL COMMENT '关联订单', after_sale_case_id BIGINT NULL COMMENT '关联售后', actor_user_id BIGINT NULL COMMENT '人工操作者',
 reason VARCHAR(500) NULL COMMENT '操作原因', budget_delta_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '预算总额增减',
 budget_after DECIMAL(18,2) NOT NULL COMMENT '变化后预算总额', evidence_reference VARCHAR(500) NULL COMMENT '完整人工凭据引用',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_marketing_budget_entry_operation(budget_id,operation_key), KEY ix_marketing_budget_entry_cursor(budget_id,id), KEY ix_marketing_budget_entry_order(order_id,id),
 CONSTRAINT fk_marketing_budget_entry_budget FOREIGN KEY(budget_id) REFERENCES marketing_budgets(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_entry_hold FOREIGN KEY(hold_id) REFERENCES marketing_budget_holds(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_entry_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_entry_case FOREIGN KEY(after_sale_case_id) REFERENCES after_sale_cases(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_budget_entry_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_budget_entry_kind CHECK(event_kind IN ('RESERVE','TRANSFER','COMMIT','RELEASE','REFUND','ADJUST')),
 CONSTRAINT ck_marketing_budget_entry_balance CHECK(held_after>=0 AND spent_after>=0 AND budget_after>=0 AND held_after+spent_after<=budget_after),
 CONSTRAINT ck_marketing_budget_entry_adjust CHECK((event_kind='ADJUST' AND actor_user_id IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason))>0 AND evidence_reference IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_reference))>0 AND budget_delta_amount<>0 AND budget_after-budget_delta_amount>=0 AND held_delta=0 AND spent_delta=0) OR (event_kind<>'ADJUST' AND budget_delta_amount=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预算追加流水及人工调整凭据';

CREATE TABLE marketing_user_quotas (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '账号参与额度ID', activity_id BIGINT NOT NULL COMMENT '最近关联活动版本',
 activity_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '跨版本活动码', user_id BIGINT NOT NULL COMMENT '参与账号',
 quota_kind VARCHAR(24) NOT NULL COMMENT '额度类型', period_key VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全期或北京时间日期',
 reserved_count INT NOT NULL DEFAULT 0 COMMENT '尚未确认占用量', consumed_count INT NOT NULL DEFAULT 0 COMMENT '已确认消耗量', version BIGINT NOT NULL DEFAULT 0 COMMENT '额度版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_quota(activity_code,user_id,quota_kind,period_key),
 CONSTRAINT fk_marketing_quota_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_quota_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_quota_kind CHECK(quota_kind IN ('BUY','HELP','OPEN_BARGAIN','COUPON_CLAIM')),
 CONSTRAINT ck_marketing_quota_balance CHECK(reserved_count>=0 AND consumed_count>=0 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨活动版本账号参与额度';

CREATE TABLE marketing_coupon_rules (
 activity_id BIGINT PRIMARY KEY COMMENT '券活动版本', face_amount DECIMAL(18,2) NOT NULL COMMENT '券面额',
 threshold_amount DECIMAL(18,2) NOT NULL COMMENT '使用门槛', issue_limit INT NOT NULL COMMENT '总发放上限', per_user_limit INT NOT NULL COMMENT '每账号限领',
 issued_count INT NOT NULL DEFAULT 0 COMMENT '累计发放张数', claim_end_at DATETIME(3) NOT NULL COMMENT '领取截止时间', validity_days INT NOT NULL COMMENT '相对有效天数',
 fixed_expire_at DATETIME(3) NULL COMMENT '固定到期上限', grant_mode VARCHAR(16) NOT NULL COMMENT '发放来源模式',
 member_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '月权益对应等级',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 KEY ix_marketing_coupon_level(member_level_code,grant_mode,activity_id),
 CONSTRAINT fk_marketing_coupon_rule_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_coupon_rule_level FOREIGN KEY(member_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_coupon_rule_amount CHECK(face_amount>0 AND threshold_amount>=face_amount),
 CONSTRAINT ck_marketing_coupon_rule_limits CHECK(issue_limit>0 AND per_user_limit>0 AND issued_count>=0 AND issued_count<=issue_limit AND validity_days BETWEEN 1 AND 366),
 CONSTRAINT ck_marketing_coupon_rule_mode CHECK((grant_mode='MEMBERSHIP' AND member_level_code IS NOT NULL AND validity_days=30) OR (grant_mode IN ('CLAIM','CODE','COMPENSATION') AND member_level_code IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券模板及月度权益关联';

CREATE TABLE marketing_coupon_codes (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '兑换码记录ID', activity_id BIGINT NOT NULL COMMENT '券活动版本', code_hash BINARY(32) NOT NULL COMMENT '兑换码校验摘要不存明文',
 code_kind VARCHAR(12) NOT NULL COMMENT '公共或一次性码', max_redemptions INT NOT NULL COMMENT '可兑换总次数', redeemed_count INT NOT NULL COMMENT '已兑换次数',
 expires_at DATETIME(3) NOT NULL COMMENT '兑换截止时间', status VARCHAR(12) NOT NULL COMMENT '码状态', creator_user_id BIGINT NOT NULL COMMENT '批次创建人',
 creation_request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始批次请求键',
 creation_request_hash BINARY(32) NOT NULL COMMENT '有序批次请求SHA256摘要', creation_ordinal INT NOT NULL COMMENT '原请求数组序号从一开始',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_code_hash(code_hash), UNIQUE KEY uk_marketing_code_request(creator_user_id,activity_id,creation_request_key,creation_ordinal), KEY ix_marketing_code_activity(activity_id,status,id),
 CONSTRAINT fk_marketing_code_rule FOREIGN KEY(activity_id) REFERENCES marketing_coupon_rules(activity_id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_code_creator FOREIGN KEY(creator_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_code_kind CHECK(code_kind IN ('PUBLIC','SINGLE') AND (code_kind<>'SINGLE' OR max_redemptions=1)),
 CONSTRAINT ck_marketing_code_count CHECK(max_redemptions>0 AND redeemed_count>=0 AND redeemed_count<=max_redemptions),
 CONSTRAINT ck_marketing_code_status CHECK(status IN ('ACTIVE','DISABLED')),
 CONSTRAINT ck_marketing_code_request CHECK(creation_ordinal BETWEEN 1 AND 100 AND creation_request_key REGEXP '^[!-~]{1,64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='兑换码摘要与创建批次';

CREATE TABLE marketing_coupon_grants (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '发券义务ID', user_id BIGINT NOT NULL COMMENT '收券账号', activity_id BIGINT NULL COMMENT '券模板缺失时为空',
 source_kind VARCHAR(24) NOT NULL COMMENT '发放来源类型', source_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定资格或人工补偿来源键',
 benefit_month DATE NULL COMMENT '月权益月份首日', eligible_level_code VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '资格时的会员等级',
 entitled_count INT NOT NULL COMMENT '应发张数', face_amount DECIMAL(18,2) NOT NULL COMMENT '资格面额快照', threshold_amount DECIMAL(18,2) NOT NULL COMMENT '资格门槛快照',
 expires_at DATETIME(3) NOT NULL COMMENT '固定权益期限', status VARCHAR(24) NOT NULL COMMENT '义务执行状态', next_attempt_at DATETIME(3) NOT NULL COMMENT '下次可尝试时间',
 claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '认领令牌', claim_until DATETIME(3) NULL COMMENT '认领租约截止', error_code VARCHAR(64) NULL COMMENT '最近执行错误码',
 code_id BIGINT NULL COMMENT '兑换来源记录', request_hash BINARY(32) NULL COMMENT '补偿命令原请求摘要', actor_user_id BIGINT NULL COMMENT '补偿操作人',
 reason VARCHAR(500) NULL COMMENT '完整补偿原因', evidence_reference VARCHAR(500) NULL COMMENT '完整补偿凭据引用',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_grant_source(user_id,source_kind,source_key), UNIQUE KEY uk_marketing_grant_actor_request(actor_user_id,activity_id,source_key),
 KEY ix_marketing_grant_due(status,next_attempt_at,id), KEY ix_marketing_grant_month(user_id,benefit_month,id),
 CONSTRAINT fk_marketing_grant_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_grant_rule FOREIGN KEY(activity_id) REFERENCES marketing_coupon_rules(activity_id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_grant_level FOREIGN KEY(eligible_level_code) REFERENCES membership_levels(level_code) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_grant_code FOREIGN KEY(code_id) REFERENCES marketing_coupon_codes(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_grant_actor FOREIGN KEY(actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_grant_kind CHECK(source_kind IN ('MEMBERSHIP','CLAIM','CODE','COMPENSATION')),
 CONSTRAINT ck_marketing_grant_status CHECK(status IN ('PENDING','GRANTED','EXPIRED','ATTENTION')),
 CONSTRAINT ck_marketing_grant_amount CHECK(entitled_count>0 AND face_amount>0 AND threshold_amount>=face_amount),
 CONSTRAINT ck_marketing_grant_claim CHECK((claim_token IS NULL AND claim_until IS NULL) OR (claim_token IS NOT NULL AND claim_until IS NOT NULL)),
 CONSTRAINT ck_marketing_grant_membership CHECK((source_kind='MEMBERSHIP' AND benefit_month IS NOT NULL AND DAYOFMONTH(benefit_month)=1 AND eligible_level_code IS NOT NULL AND actor_user_id IS NULL AND request_hash IS NULL) OR (source_kind<>'MEMBERSHIP' AND activity_id IS NOT NULL)),
 CONSTRAINT ck_marketing_grant_compensation CHECK(source_kind<>'COMPENSATION' OR (activity_id IS NOT NULL AND actor_user_id IS NOT NULL AND request_hash IS NOT NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason))>0 AND evidence_reference IS NOT NULL AND CHAR_LENGTH(TRIM(evidence_reference))>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可恢复发券资格义务及人工证据';

CREATE TABLE user_coupons (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '持券ID', grant_id BIGINT NOT NULL COMMENT '原发券义务', ordinal_no INT NOT NULL COMMENT '义务内张数序号',
 user_id BIGINT NOT NULL COMMENT '持券账号', activity_id BIGINT NOT NULL COMMENT '券模板版本',
 face_amount DECIMAL(18,2) NOT NULL COMMENT '实际面额快照', threshold_amount DECIMAL(18,2) NOT NULL COMMENT '实际门槛快照',
 valid_from DATETIME(3) NOT NULL COMMENT '有效起始含边界', expires_at DATETIME(3) NOT NULL COMMENT '有效截止不含边界', status VARCHAR(16) NOT NULL COMMENT '持券状态',
 reserved_order_id BIGINT NULL COMMENT '当前占用订单', used_order_id BIGINT NULL COMMENT '当前使用订单',
 reservation_version BIGINT NOT NULL DEFAULT 0 COMMENT '占用轮次', reserved_until DATETIME(3) NULL COMMENT '本轮订单付款截止',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_user_coupon_ordinal(grant_id,ordinal_no), KEY ix_user_coupon_owner(user_id,status,expires_at,id), KEY ix_user_coupon_release(status,reserved_until,id),
 CONSTRAINT fk_user_coupon_grant FOREIGN KEY(grant_id) REFERENCES marketing_coupon_grants(id) ON DELETE RESTRICT,
 CONSTRAINT fk_user_coupon_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT fk_user_coupon_rule FOREIGN KEY(activity_id) REFERENCES marketing_coupon_rules(activity_id) ON DELETE RESTRICT,
 CONSTRAINT fk_user_coupon_reserved_order FOREIGN KEY(reserved_order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT fk_user_coupon_used_order FOREIGN KEY(used_order_id) REFERENCES orders(id) ON DELETE RESTRICT,
 CONSTRAINT ck_user_coupon_status CHECK(status IN ('AVAILABLE','RESERVED','USED','EXPIRED')),
 CONSTRAINT ck_user_coupon_values CHECK(ordinal_no>0 AND face_amount>0 AND threshold_amount>=face_amount AND expires_at>valid_from AND reservation_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户持券与占用轮次';

CREATE TABLE order_promotion_allocations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '订单项优惠承担快照ID', order_item_id BIGINT NOT NULL COMMENT '成交订单项', activity_id BIGINT NOT NULL COMMENT '原活动版本',
 benefit_kind VARCHAR(24) NOT NULL COMMENT '优惠或实物成本类型', user_coupon_id BIGINT NULL COMMENT '使用的券', tier_id BIGINT NULL COMMENT '使用的阶梯',
 amount DECIMAL(18,2) NOT NULL COMMENT '分摊优惠或成本总额', platform_amount DECIMAL(18,2) NOT NULL COMMENT '平台承担金额', merchant_amount DECIMAL(18,2) NOT NULL COMMENT '商家承担金额',
 platform_share_bp INT NOT NULL COMMENT '成交平台承担万分比', unit_cost_amount DECIMAL(18,2) NULL COMMENT '实物单件供货成本', rule_snapshot JSON NOT NULL COMMENT '不可变解释和计算输入',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 UNIQUE KEY uk_order_promotion_kind(order_item_id,activity_id,benefit_kind), KEY ix_order_promotion_coupon(user_coupon_id,id), KEY ix_order_promotion_activity(activity_id,id),
 CONSTRAINT fk_order_promotion_item FOREIGN KEY(order_item_id) REFERENCES order_items(id) ON DELETE RESTRICT,
 CONSTRAINT fk_order_promotion_activity FOREIGN KEY(activity_id) REFERENCES marketing_activities(id) ON DELETE RESTRICT,
 CONSTRAINT fk_order_promotion_coupon FOREIGN KEY(user_coupon_id) REFERENCES user_coupons(id) ON DELETE RESTRICT,
 CONSTRAINT fk_order_promotion_tier FOREIGN KEY(tier_id) REFERENCES marketing_threshold_tiers(id) ON DELETE RESTRICT,
 CONSTRAINT ck_order_promotion_kind CHECK(benefit_kind IN ('MEMBER','LIMITED','FLASH','GROUP','BARGAIN','FULL_REDUCTION','COUPON','GIFT_COST','POINTS_COST')),
 CONSTRAINT ck_order_promotion_amount CHECK(amount>=0 AND platform_amount>=0 AND merchant_amount>=0 AND amount=platform_amount+merchant_amount AND platform_share_bp BETWEEN 0 AND 10000 AND (unit_cost_amount IS NULL OR unit_cost_amount>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='逐订单项优惠与供货成本承担快照';

CREATE TABLE marketing_refund_allocations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '退款权益承担冲回ID', after_sale_item_id BIGINT NOT NULL COMMENT '原售后明细', promotion_allocation_id BIGINT NULL COMMENT '原优惠成本快照',
 benefit_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '基础退款或优惠类别键', operation_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定冲回业务键',
 cash_refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '现金退款额', platform_reversal_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '平台承担冲回',
 merchant_reversal_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '商家承担冲回', growth_reversal_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成长冲回额',
 point_reversal_units BIGINT NOT NULL DEFAULT 0 COMMENT '积分冲回单位', status VARCHAR(16) NOT NULL COMMENT '冲回执行状态', applied_at DATETIME(3) NULL COMMENT '实际完成时间',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_refund_operation(operation_key), UNIQUE KEY uk_marketing_refund_benefit(after_sale_item_id,benefit_key), KEY ix_marketing_refund_status(status,id),
 CONSTRAINT fk_marketing_refund_item FOREIGN KEY(after_sale_item_id) REFERENCES after_sale_items(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_refund_promotion FOREIGN KEY(promotion_allocation_id) REFERENCES order_promotion_allocations(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_refund_status CHECK(status IN ('PENDING','APPLIED')),
 CONSTRAINT ck_marketing_refund_amount CHECK(cash_refund_amount>=0 AND platform_reversal_amount>=0 AND merchant_reversal_amount>=0 AND growth_reversal_amount>=0 AND point_reversal_units>=0),
 CONSTRAINT ck_marketing_refund_base CHECK((benefit_key='BASE' AND promotion_allocation_id IS NULL) OR (benefit_key<>'BASE' AND promotion_allocation_id IS NOT NULL AND cash_refund_amount=0 AND growth_reversal_amount=0 AND point_reversal_units=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款现金权益及优惠承担冲回明细';

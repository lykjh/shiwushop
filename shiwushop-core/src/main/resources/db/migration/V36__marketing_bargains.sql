-- MK-05 bargain progress and helper facts.
CREATE TABLE marketing_bargains (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '砍价单ID',
 participation_id BIGINT NOT NULL COMMENT '占用活动库存的参与ID',
 activity_sku_id BIGINT NOT NULL COMMENT '活动SKU范围',
 user_id BIGINT NOT NULL COMMENT '发起账号',
 start_amount DECIMAL(18,2) NOT NULL COMMENT '公示原价',
 target_amount DECIMAL(18,2) NOT NULL COMMENT '公示目标价',
 current_amount DECIMAL(18,2) NOT NULL COMMENT '当前进度价',
 required_helpers INT NOT NULL COMMENT '公示助力步数',
 help_count INT NOT NULL DEFAULT 0 COMMENT '已完成助力步数',
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '砍价状态',
 help_expires_at DATETIME(3) NOT NULL COMMENT '助力绝对截止时刻',
 purchase_expires_at DATETIME(3) NULL COMMENT '达标购买绝对截止时刻',
 active_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ACTIVE','QUALIFIED') THEN user_id ELSE NULL END) STORED COMMENT '当前有效资格账号唯一键',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '进度版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
 UNIQUE KEY uk_marketing_bargain_participation(participation_id),
 UNIQUE KEY uk_marketing_bargain_active(activity_sku_id,active_user_id),
 KEY ix_marketing_bargain_help_due(status,help_expires_at,id),
 KEY ix_marketing_bargain_purchase_due(status,purchase_expires_at,id),
 CONSTRAINT fk_marketing_bargain_participation FOREIGN KEY(participation_id) REFERENCES marketing_participations(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_bargain_scope FOREIGN KEY(activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_bargain_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_bargain_status CHECK (status IN ('ACTIVE','QUALIFIED','ORDERED','EXPIRED','CLOSED')),
 CONSTRAINT ck_marketing_bargain_progress CHECK (target_amount>0 AND target_amount<=current_amount AND current_amount<=start_amount AND required_helpers BETWEEN 1 AND 100 AND help_count BETWEEN 0 AND required_helpers AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='砍价进度与购买资格';

CREATE TABLE marketing_bargain_helps (
 id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '砍价助力ID',
 bargain_id BIGINT NOT NULL COMMENT '所属砍价单',
 helper_user_id BIGINT NOT NULL COMMENT '助力账号',
 request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '助力请求幂等键',
 step_no INT NOT NULL COMMENT '固定助力步序',
 amount_before DECIMAL(18,2) NOT NULL COMMENT '助力前进度价',
 decrease_amount DECIMAL(18,2) NOT NULL COMMENT '本步固定降额',
 amount_after DECIMAL(18,2) NOT NULL COMMENT '助力后进度价',
 helped_at DATETIME(3) NOT NULL COMMENT '有效助力时刻',
 UNIQUE KEY uk_marketing_bargain_helper(bargain_id,helper_user_id),
 UNIQUE KEY uk_marketing_bargain_help_request(helper_user_id,request_key),
 UNIQUE KEY uk_marketing_bargain_step(bargain_id,step_no),
 KEY ix_marketing_bargain_helper_day(helper_user_id,helped_at,id),
 CONSTRAINT fk_marketing_bargain_help_root FOREIGN KEY(bargain_id) REFERENCES marketing_bargains(id) ON DELETE RESTRICT,
 CONSTRAINT fk_marketing_bargain_help_user FOREIGN KEY(helper_user_id) REFERENCES users(id) ON DELETE RESTRICT,
 CONSTRAINT ck_marketing_bargain_help_request CHECK (request_key REGEXP '^[!-~]{1,64}$'),
 CONSTRAINT ck_marketing_bargain_help_step CHECK (step_no>0 AND decrease_amount>0 AND amount_before>amount_after AND amount_after>=0 AND amount_before-amount_after=decrease_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定步进砍价助力事实';


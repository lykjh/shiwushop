-- 开发二：私有资质媒体、入驻申请及店员授权。存量媒体保持PUBLIC。
ALTER TABLE media_assets ADD COLUMN purpose VARCHAR(12) NOT NULL DEFAULT 'PUBLIC' COMMENT '公开业务图片或私有资质图片';
ALTER TABLE media_assets ADD CONSTRAINT ck_media_assets_purpose CHECK (purpose IN ('PUBLIC','CREDENTIAL'));

-- 存量店铺无申请，空有效期沿用原 enabled 规则；新获批店铺记录证照边界。
ALTER TABLE shops ADD COLUMN qualification_valid_until DATE NULL COMMENT '当前获批资质最早到期日';
ALTER TABLE shops ADD COLUMN qualification_revoked TINYINT NOT NULL DEFAULT 0 COMMENT '资质已撤回';
ALTER TABLE shops ADD CONSTRAINT ck_shops_qualification_revoked CHECK (qualification_revoked IN (0,1));
ALTER TABLE shops ADD KEY idx_shops_qualification_expiry (enabled, qualification_valid_until, id);

CREATE TABLE merchant_applications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    applicant_user_id BIGINT NOT NULL,
    shop_id BIGINT NULL,
    shop_name VARCHAR(100) NOT NULL,
    business_type VARCHAR(20) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version BIGINT NOT NULL DEFAULT 1,
    reject_reason VARCHAR(500) NULL,
    submitted_at DATETIME(3) NULL,
    reviewed_by_user_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_merchant_applications_shop_id (shop_id),
    KEY idx_merchant_applications_applicant_status (applicant_user_id, status, id),
    KEY idx_merchant_applications_status_id (status, id),
    CONSTRAINT fk_merchant_applications_applicant FOREIGN KEY (applicant_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_applications_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_applications_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_merchant_applications_business_type CHECK (business_type IN ('PERSONAL','REGISTERED','FOOD_HANDLING')),
    CONSTRAINT ck_merchant_applications_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT ck_merchant_applications_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家入驻申请';

CREATE TABLE merchant_application_documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    application_version BIGINT NOT NULL,
    document_type VARCHAR(24) NOT NULL,
    media_id BIGINT NOT NULL,
    valid_until DATE NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_merchant_application_documents_type (application_id, application_version, document_type),
    KEY idx_merchant_application_documents_expiry (valid_until, application_id),
    CONSTRAINT fk_merchant_application_documents_application FOREIGN KEY (application_id) REFERENCES merchant_applications(id) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_application_documents_media FOREIGN KEY (media_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    CONSTRAINT ck_merchant_application_documents_type CHECK (document_type IN ('IDENTITY','BUSINESS_LICENSE','FOOD_LICENSE','HEALTH_CERT')),
    CONSTRAINT ck_merchant_application_documents_version CHECK (application_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申请当前资质引用，媒体用途须为私有资质';

CREATE TABLE merchant_application_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_id BIGINT NOT NULL,
    application_version BIGINT NOT NULL,
    decision VARCHAR(8) NOT NULL,
    reason VARCHAR(500) NULL,
    reviewed_by_user_id BIGINT NOT NULL,
    reviewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_merchant_application_reviews_version (application_id, application_version),
    CONSTRAINT fk_merchant_application_reviews_application FOREIGN KEY (application_id) REFERENCES merchant_applications(id) ON DELETE RESTRICT,
    CONSTRAINT fk_merchant_application_reviews_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_merchant_application_reviews_decision CHECK (decision IN ('APPROVED','REJECTED')),
    CONSTRAINT ck_merchant_application_reviews_version CHECK (application_version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入驻审核版本与驳回理由';
CREATE TABLE shop_staff_grants (
    shop_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    can_catalog TINYINT NOT NULL DEFAULT 0,
    can_inventory TINYINT NOT NULL DEFAULT 0,
    can_fulfillment TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    granted_by_user_id BIGINT NOT NULL,
    granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_by_user_id BIGINT NULL,
    revoked_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (shop_id, user_id),
    KEY idx_shop_staff_grants_user_status (user_id, status, shop_id),
    CONSTRAINT fk_shop_staff_grants_shop FOREIGN KEY (shop_id) REFERENCES shops(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_staff_grants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_staff_grants_granter FOREIGN KEY (granted_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_staff_grants_revoker FOREIGN KEY (revoked_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_staff_grants_roles CHECK (can_catalog IN (0,1) AND can_inventory IN (0,1) AND can_fulfillment IN (0,1)),
    CONSTRAINT ck_shop_staff_grants_status CHECK (status IN ('ACTIVE','REVOKED')),
    CONSTRAINT ck_shop_staff_grants_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺员工权限，转店同事务撤销';


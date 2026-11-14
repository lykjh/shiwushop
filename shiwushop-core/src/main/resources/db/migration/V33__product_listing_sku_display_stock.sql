-- 开发二：商品审核、SKU停售、展示参数和营销普通库存原因。保留历史ON_SALE。
ALTER TABLE products MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '草稿、待审、上架、下架、驳回或归档';
ALTER TABLE products ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '待审资料修订号';
ALTER TABLE products DROP CHECK ck_products_1;
ALTER TABLE products ADD CONSTRAINT ck_products_1 CHECK (status IN ('DRAFT','PENDING_REVIEW','REJECTED','ON_SALE','OFF_SALE','ARCHIVED'));

CREATE TABLE product_listing_submissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(8) NOT NULL DEFAULT 'PENDING',
    submitted_by_user_id BIGINT NOT NULL,
    submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_by_user_id BIGINT NULL,
    reviewed_at DATETIME(3) NULL,
    reason VARCHAR(500) NULL,
    UNIQUE KEY uk_product_listing_submissions_revision (product_id, revision),
    KEY idx_product_listing_submissions_status_id (status, id),
    CONSTRAINT fk_product_listing_submissions_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_listing_submissions_submitter FOREIGN KEY (submitted_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_product_listing_submissions_reviewer FOREIGN KEY (reviewed_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_listing_submissions_status CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_product_listing_submissions_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品上架审核版本';

ALTER TABLE skus ADD COLUMN status VARCHAR(8) NOT NULL DEFAULT 'ACTIVE' COMMENT 'SKU可售状态';
ALTER TABLE skus ADD CONSTRAINT ck_skus_3 CHECK (status IN ('ACTIVE','STOPPED'));

CREATE TABLE product_display_attributes (
    product_id BIGINT NOT NULL,
    attribute_key VARCHAR(40) NOT NULL,
    attribute_value VARCHAR(200) NOT NULL,
    position TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (product_id, attribute_key),
    KEY idx_product_display_attributes_order (product_id, position, attribute_key),
    CONSTRAINT fk_product_display_attributes_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_display_attributes_position CHECK (position <= 19)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='非销售展示参数，不参与SKU签名';

ALTER TABLE inventory_movements DROP CHECK ck_inventory_movements_1;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inventory_movements_1 CHECK (reason IN ('ORDER_DEDUCT','ORDER_CANCEL_RETURN','REFUND_RETURN','MERCHANT_ADJUST','MARKETING_ALLOCATE','MARKETING_RELEASE'));
ALTER TABLE inventory_movements MODIFY COLUMN operation_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL COMMENT '大小写敏感的库存操作幂等业务键';
ALTER TABLE inventory_movements ADD COLUMN actor_user_id BIGINT NULL COMMENT '人工活动划拨操作者';
ALTER TABLE inventory_movements ADD COLUMN activity_sku_id BIGINT NULL COMMENT '划拨来源活动SKU范围';
ALTER TABLE inventory_movements ADD CONSTRAINT fk_inventory_movements_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE inventory_movements ADD CONSTRAINT fk_inventory_movements_activity_sku FOREIGN KEY (activity_sku_id) REFERENCES marketing_activity_skus(id) ON DELETE RESTRICT;
ALTER TABLE inventory_movements ADD CONSTRAINT ck_inventory_movements_marketing_source CHECK (reason NOT IN ('MARKETING_ALLOCATE','MARKETING_RELEASE') OR (actor_user_id IS NOT NULL AND activity_sku_id IS NOT NULL));


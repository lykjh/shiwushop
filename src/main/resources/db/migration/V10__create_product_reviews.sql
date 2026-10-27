CREATE TABLE product_reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    buyer_user_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    follow_up VARCHAR(2000) NULL,
    follow_up_at DATETIME NULL,
    deleted_at DATETIME NULL,
    hidden_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_reviews_order_item (order_item_id),
    KEY idx_product_reviews_product_id (product_id, id),
    CONSTRAINT fk_product_reviews_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_product_reviews_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_product_reviews_buyer FOREIGN KEY (buyer_user_id) REFERENCES users (id),
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_product_reviews_follow_up CHECK ((follow_up IS NULL) = (follow_up_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一条订单明细终生一次商品评价';

CREATE TABLE product_review_images (
    review_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    phase VARCHAR(10) NOT NULL,
    position TINYINT NOT NULL,
    PRIMARY KEY (review_id, phase, position),
    CONSTRAINT fk_product_review_images_review FOREIGN KEY (review_id) REFERENCES product_reviews (id),
    CONSTRAINT fk_product_review_images_media FOREIGN KEY (media_id) REFERENCES media_assets (id),
    CONSTRAINT ck_product_review_images_phase CHECK (phase IN ('ORIGINAL', 'FOLLOW_UP')),
    CONSTRAINT ck_product_review_images_position CHECK (position BETWEEN 0 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价和追评图片';

CREATE TABLE product_review_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
    decision VARCHAR(8) NULL,
    handled_by_user_id BIGINT NULL,
    handled_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_review_reports_reviewer (review_id, reporter_user_id),
    KEY idx_product_review_reports_status (status, id),
    CONSTRAINT fk_product_review_reports_review FOREIGN KEY (review_id) REFERENCES product_reviews (id),
    CONSTRAINT fk_product_review_reports_reporter FOREIGN KEY (reporter_user_id) REFERENCES users (id),
    CONSTRAINT fk_product_review_reports_handler FOREIGN KEY (handled_by_user_id) REFERENCES users (id),
    CONSTRAINT ck_product_review_reports_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_product_review_reports_decision CHECK (decision IS NULL OR decision IN ('KEEP', 'HIDE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公开评价举报';

CREATE TABLE product_review_moderation_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    review_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    admin_user_id BIGINT NOT NULL,
    decision VARCHAR(8) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_product_review_audit_review FOREIGN KEY (review_id) REFERENCES product_reviews (id),
    CONSTRAINT fk_product_review_audit_report FOREIGN KEY (report_id) REFERENCES product_review_reports (id),
    CONSTRAINT fk_product_review_audit_admin FOREIGN KEY (admin_user_id) REFERENCES users (id),
    CONSTRAINT ck_product_review_audit_decision CHECK (decision IN ('KEEP', 'HIDE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价举报处理记录';

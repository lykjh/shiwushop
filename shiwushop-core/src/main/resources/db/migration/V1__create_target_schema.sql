-- Baseline for an empty shiwushop database; legacy data is not imported.
-- The baseline creates 32 tables; later migrations update this schema.

CREATE TABLE `categories` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `parent_id` BIGINT NULL COMMENT '父分类ID',
    `level` TINYINT NOT NULL COMMENT '分类层级',
    `name` VARCHAR(100) NOT NULL COMMENT '分类名称',
    `visible` TINYINT NOT NULL DEFAULT 0 COMMENT '是否公开展示',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序值',
    `parent_key` BIGINT GENERATED ALWAYS AS (COALESCE(parent_id,0)) STORED COMMENT '父分类ID空值归一化键，用于同级名称唯一约束',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_categories_parent_key_name` (`parent_key`,`name`),
    CONSTRAINT `fk_categories_parent_id` FOREIGN KEY (`parent_id`) REFERENCES `categories` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_categories_1` CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT `ck_categories_2` CHECK (visible IN (0,1)),
    CONSTRAINT `ck_categories_3` CHECK (sort_order >= 0),
    CONSTRAINT `ck_categories_4` CHECK ((level = 1 AND parent_id IS NULL) OR (level > 1 AND parent_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类树';

CREATE TABLE `consumed_events` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `consumer_name` VARCHAR(64) NOT NULL COMMENT '消息消费者名称',
    `event_id` BIGINT NOT NULL COMMENT '已消费事件ID',
    `consumed_at` DATETIME NOT NULL COMMENT '消息消费完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_consumed_events_consumer_name_event_id` (`consumer_name`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消费者幂等记录';

CREATE TABLE `outbox_events` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT '业务事件类型',
    `aggregate_type` VARCHAR(32) NOT NULL COMMENT '事件所属业务对象类型',
    `aggregate_id` BIGINT NOT NULL COMMENT '事件所属业务对象ID',
    `payload` JSON NOT NULL COMMENT '不含隐私的事件负载',
    `status` VARCHAR(12) NOT NULL COMMENT '消息待投递、已投递或死信状态',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '消息投递尝试次数',
    `next_attempt_at` DATETIME NOT NULL COMMENT '下次投递时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `published_at` DATETIME NULL COMMENT '消息成功投递时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_outbox_events_1` CHECK (status IN ('PENDING','PUBLISHED','DEAD')),
    CONSTRAINT `ck_outbox_events_2` CHECK (attempts >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='待投递业务事件';

CREATE TABLE `users` (
    `id` BIGINT AUTO_INCREMENT COMMENT '用户主体ID',
    `nickname` VARCHAR(80) NULL COMMENT '公开展示昵称',
    `avatar_media_id` BIGINT NULL COMMENT '头像媒体ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户主体';

CREATE TABLE `addresses` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    `consignee_ciphertext` VARBINARY(512) NOT NULL COMMENT '加密后的收货人姓名',
    `phone_ciphertext` VARBINARY(512) NOT NULL COMMENT '加密后的手机号码',
    `province` VARCHAR(64) NOT NULL COMMENT '收货省份',
    `city` VARCHAR(64) NOT NULL COMMENT '收货城市',
    `district` VARCHAR(64) NOT NULL COMMENT '收货区县',
    `detail_ciphertext` VARBINARY(1024) NOT NULL COMMENT '加密后的详细收货地址',
    `key_version` SMALLINT UNSIGNED NOT NULL COMMENT '隐私字段加密密钥版本',
    `is_default` TINYINT NOT NULL DEFAULT 0 COMMENT '是否为默认地址',
    `deleted_at` DATETIME NULL COMMENT '地址软删除时间',
    `default_user_id` BIGINT GENERATED ALWAYS AS (CASE WHEN is_default=1 AND deleted_at IS NULL THEN user_id ELSE NULL END) STORED COMMENT '有效默认地址的用户唯一约束生成值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_addresses_default_user_id` (`default_user_id`),
    CONSTRAINT `fk_addresses_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_addresses_1` CHECK (is_default IN (0,1)),
    CONSTRAINT `ck_addresses_2` CHECK (key_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家保存的收货地址';

CREATE TABLE `audit_events` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `actor_user_id` BIGINT NULL COMMENT '操作人用户ID，系统任务为空',
    `action` VARCHAR(64) NOT NULL COMMENT '审计操作类型',
    `target_type` VARCHAR(32) NOT NULL COMMENT '被操作业务对象类型',
    `target_id` BIGINT NOT NULL COMMENT '被操作业务对象ID',
    `request_id` VARCHAR(64) NULL COMMENT '请求追踪ID',
    `summary` VARCHAR(500) NULL COMMENT '不含隐私的操作摘要',
    `occurred_at` DATETIME NOT NULL COMMENT '操作发生时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_audit_events_actor_user_id` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感操作审计';

CREATE TABLE `checkout_requests` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    `idempotency_key` VARCHAR(64) NOT NULL COMMENT '客户端结算幂等键',
    `request_hash` BINARY(32) NOT NULL COMMENT '结算请求内容SHA-256摘要',
    `status` VARCHAR(12) NOT NULL COMMENT '结算处理状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_checkout_requests_user_id_idempotency_key` (`user_id`,`idempotency_key`),
    CONSTRAINT `fk_checkout_requests_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_checkout_requests_1` CHECK (status IN ('PROCESSING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算幂等请求';

CREATE TABLE `media_assets` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `uploaded_by_user_id` BIGINT NOT NULL COMMENT '上传者用户ID',
    `storage_key` VARCHAR(255) NOT NULL COMMENT '对象存储内部路径',
    `mime_type` VARCHAR(32) NOT NULL COMMENT '图片媒体类型',
    `byte_size` BIGINT NOT NULL COMMENT '原始图片字节数',
    `width` INT NOT NULL COMMENT '图片宽度像素数',
    `height` INT NOT NULL COMMENT '图片高度像素数',
    `sha256` BINARY(32) NOT NULL COMMENT '原始图片SHA-256摘要',
    `status` VARCHAR(12) NOT NULL COMMENT '图片暂存、可用或删除状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_media_assets_storage_key` (`storage_key`),
    CONSTRAINT `fk_media_assets_uploaded_by_user_id` FOREIGN KEY (`uploaded_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_media_assets_1` CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT `ck_media_assets_2` CHECK (byte_size > 0 AND byte_size <= 10485760),
    CONSTRAINT `ck_media_assets_3` CHECK (width > 0 AND height > 0 AND width * 1.0 * height <= 20000000),
    CONSTRAINT `ck_media_assets_4` CHECK (status IN ('STAGED','READY','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='受控图片元数据';

CREATE TABLE `user_accounts` (
    `user_id` BIGINT COMMENT '所属用户ID',
    `username` VARCHAR(100) NOT NULL COMMENT '登录名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码单向散列值',
    `role` VARCHAR(10) NOT NULL COMMENT '账号角色',
    `status` VARCHAR(10) NOT NULL COMMENT '账号启用或禁用状态',
    `password_changed_at` DATETIME NULL COMMENT '最近修改密码时间',
    `auth_version` INT NOT NULL DEFAULT 0 COMMENT '会话撤销版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_user_accounts_username` (`username`),
    CONSTRAINT `fk_user_accounts_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_user_accounts_1` CHECK (role IN ('USER','MERCHANT','ADMIN')),
    CONSTRAINT `ck_user_accounts_2` CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT `ck_user_accounts_3` CHECK (auth_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号与权限';

CREATE TABLE `user_private` (
    `user_id` BIGINT COMMENT '所属用户ID',
    `phone_ciphertext` VARBINARY(512) NULL COMMENT '加密后的手机号码',
    `email_ciphertext` VARBINARY(512) NULL COMMENT '加密后的电子邮箱',
    `key_version` SMALLINT UNSIGNED NOT NULL COMMENT '隐私字段加密密钥版本',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_private_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_user_private_1` CHECK (key_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可读取的用户隐私';

CREATE TABLE `brands` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(50) NOT NULL COMMENT '品牌名称',
    `logo_media_id` BIGINT NULL COMMENT '标识图片媒体ID',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_brands_name` (`name`),
    CONSTRAINT `fk_brands_logo_media_id` FOREIGN KEY (`logo_media_id`) REFERENCES `media_assets` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_brands_1` CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌';

CREATE TABLE `shops` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `owner_user_id` BIGINT NOT NULL COMMENT '店铺经营者用户ID',
    `name` VARCHAR(100) NOT NULL COMMENT '店铺名称',
    `logo_media_id` BIGINT NULL COMMENT '标识图片媒体ID',
    `description` VARCHAR(1000) NULL COMMENT '描述内容',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_shops_name` (`name`),
    CONSTRAINT `fk_shops_owner_user_id` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_shops_logo_media_id` FOREIGN KEY (`logo_media_id`) REFERENCES `media_assets` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_shops_1` CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺';

CREATE TABLE `orders` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    `shop_id` BIGINT NOT NULL COMMENT '所属店铺ID',
    `total_amount` DECIMAL(18,2) NOT NULL COMMENT '订单应付总金额',
    `status` VARCHAR(12) NOT NULL COMMENT '订单待付、已付或取消状态',
    `pay_expires_at` DATETIME NOT NULL COMMENT '支付截止时间',
    `paid_at` DATETIME NULL COMMENT '支付成功时间',
    `consignee_ciphertext` VARBINARY(512) NOT NULL COMMENT '加密后的收货人姓名',
    `phone_ciphertext` VARBINARY(512) NOT NULL COMMENT '加密后的手机号码',
    `province` VARCHAR(64) NOT NULL COMMENT '收货省份',
    `city` VARCHAR(64) NOT NULL COMMENT '收货城市',
    `district` VARCHAR(64) NOT NULL COMMENT '收货区县',
    `detail_ciphertext` VARBINARY(1024) NOT NULL COMMENT '加密后的详细收货地址',
    `shipping_key_version` SMALLINT UNSIGNED NOT NULL COMMENT '订单地址快照加密密钥版本',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_orders_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_orders_shop_id` FOREIGN KEY (`shop_id`) REFERENCES `shops` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_orders_1` CHECK (total_amount > 0),
    CONSTRAINT `ck_orders_2` CHECK (status IN ('UNPAID','PAID','CANCELLED')),
    CONSTRAINT `ck_orders_3` CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT `ck_orders_4` CHECK (shipping_key_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一张店铺订单';

CREATE TABLE `products` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `shop_id` BIGINT NOT NULL COMMENT '所属店铺ID',
    `category_id` BIGINT NOT NULL COMMENT '所属分类ID',
    `name` VARCHAR(100) NOT NULL COMMENT '商品名称',
    `description` TEXT NULL COMMENT '描述内容',
    `status` VARCHAR(10) NOT NULL COMMENT '商品草稿、上架或下架状态',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_products_shop_id` FOREIGN KEY (`shop_id`) REFERENCES `shops` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_products_category_id` FOREIGN KEY (`category_id`) REFERENCES `categories` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_products_1` CHECK (status IN ('DRAFT','ON_SALE','OFF_SALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主体';

CREATE TABLE `after_sale_cases` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `type` VARCHAR(20) NOT NULL COMMENT '售后申请类型',
    `status` VARCHAR(20) NOT NULL COMMENT '售后申请流转状态',
    `reason` VARCHAR(500) NOT NULL COMMENT '买家申请售后的原因',
    `refund_amount` DECIMAL(18,2) NOT NULL COMMENT '申请退款金额',
    `reviewed_by_user_id` BIGINT NULL COMMENT '售后审核人用户ID',
    `reviewed_at` DATETIME NULL COMMENT '审核时间',
    `review_reason` VARCHAR(500) NULL COMMENT '审核说明',
    `requested_at` DATETIME NOT NULL COMMENT '申请或请求时间',
    `finished_at` DATETIME NULL COMMENT '售后结束时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_after_sale_cases_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_after_sale_cases_reviewed_by_user_id` FOREIGN KEY (`reviewed_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_after_sale_cases_1` CHECK (type IN ('REFUND_ONLY','RETURN_REFUND')),
    CONSTRAINT `ck_after_sale_cases_2` CHECK (status IN ('REQUESTED','REJECTED','WAITING_RETURN','WAITING_RECEIPT','REFUNDING','REFUNDED','CLOSED')),
    CONSTRAINT `ck_after_sale_cases_3` CHECK (refund_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一张售后申请';

CREATE TABLE `checkout_request_orders` (
    `checkout_request_id` BIGINT NOT NULL COMMENT '所属结算请求ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`checkout_request_id`,`order_id`),
    UNIQUE KEY `uk_checkout_request_orders_order_id` (`order_id`),
    CONSTRAINT `fk_checkout_request_orders_checkout_request_id` FOREIGN KEY (`checkout_request_id`) REFERENCES `checkout_requests` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_checkout_request_orders_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算与订单对应';

CREATE TABLE `payment_attempts` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `channel` VARCHAR(32) NOT NULL COMMENT '支付渠道标识',
    `out_trade_no` VARCHAR(64) NOT NULL COMMENT '商户支付单号',
    `alipay_trade_no` VARCHAR(64) NULL COMMENT '支付宝交易号',
    `app_id` VARCHAR(64) NOT NULL COMMENT '支付宝应用ID快照',
    `seller_id` VARCHAR(64) NOT NULL COMMENT '支付宝商户ID快照',
    `amount` DECIMAL(18,2) NOT NULL COMMENT '本次支付请求金额',
    `status` VARCHAR(12) NOT NULL COMMENT '渠道支付尝试状态',
    `active_order_id` BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN order_id ELSE NULL END) STORED COMMENT '待支付订单唯一约束生成值',
    `success_order_id` BIGINT GENERATED ALWAYS AS (CASE WHEN status='SUCCESS' THEN order_id ELSE NULL END) STORED COMMENT '成功支付订单唯一约束生成值',
    `paid_at` DATETIME NULL COMMENT '支付成功时间',
    `closed_at` DATETIME NULL COMMENT '支付尝试关闭时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_payment_attempts_out_trade_no` (`out_trade_no`),
    UNIQUE KEY `uk_payment_attempts_alipay_trade_no` (`alipay_trade_no`),
    UNIQUE KEY `uk_payment_attempts_active_order_id` (`active_order_id`),
    UNIQUE KEY `uk_payment_attempts_success_order_id` (`success_order_id`),
    CONSTRAINT `fk_payment_attempts_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_payment_attempts_1` CHECK (amount > 0),
    CONSTRAINT `ck_payment_attempts_2` CHECK (status IN ('PENDING','SUCCESS','CLOSED')),
    CONSTRAINT `ck_payment_attempts_3` CHECK ((status = 'SUCCESS') = (paid_at IS NOT NULL)),
    CONSTRAINT `ck_payment_attempts_4` CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次支付渠道尝试';

CREATE TABLE `product_brands` (
    `product_id` BIGINT COMMENT '所属商品ID',
    `brand_id` BIGINT NOT NULL COMMENT '所属品牌ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`product_id`),
    CONSTRAINT `fk_product_brands_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_product_brands_brand_id` FOREIGN KEY (`brand_id`) REFERENCES `brands` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品的可选品牌';

CREATE TABLE `product_images` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `product_id` BIGINT NOT NULL COMMENT '所属商品ID',
    `media_id` BIGINT NOT NULL COMMENT '关联图片媒体ID',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序值',
    `is_cover` TINYINT NOT NULL DEFAULT 0 COMMENT '是否为商品封面',
    `cover_product_id` BIGINT GENERATED ALWAYS AS (CASE WHEN is_cover=1 THEN product_id ELSE NULL END) STORED COMMENT '封面商品唯一约束生成值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_images_product_id_media_id` (`product_id`,`media_id`),
    UNIQUE KEY `uk_product_images_cover_product_id` (`cover_product_id`),
    CONSTRAINT `fk_product_images_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_product_images_media_id` FOREIGN KEY (`media_id`) REFERENCES `media_assets` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_product_images_1` CHECK (is_cover IN (0,1)),
    CONSTRAINT `ck_product_images_2` CHECK (sort_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品图片与封面';

CREATE TABLE `product_options` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `product_id` BIGINT NOT NULL COMMENT '所属商品ID',
    `name` VARCHAR(50) NOT NULL COMMENT '规格维度名称',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_options_product_id_name` (`product_id`,`name`),
    CONSTRAINT `fk_product_options_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_product_options_1` CHECK (sort_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规格维度';

CREATE TABLE `product_sales_stats` (
    `product_id` BIGINT COMMENT '所属商品ID',
    `sold_count` BIGINT NOT NULL DEFAULT 0 COMMENT '历史成交件数，退款不回减',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`product_id`),
    CONSTRAINT `fk_product_sales_stats_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_product_sales_stats_1` CHECK (sold_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='历史成交件数';

CREATE TABLE `shipments` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `carrier_name` VARCHAR(80) NOT NULL COMMENT '快递公司名称',
    `tracking_no` VARCHAR(100) NOT NULL COMMENT '快递运单号',
    `status` VARCHAR(12) NOT NULL COMMENT '发货或确认收货状态',
    `shipped_by_user_id` BIGINT NOT NULL COMMENT '发货操作人用户ID',
    `shipped_at` DATETIME NOT NULL COMMENT '实际发货时间',
    `auto_confirm_at` DATETIME NOT NULL COMMENT '预计自动确认收货时间',
    `confirmed_at` DATETIME NULL COMMENT '确认收货时间',
    `confirm_source` VARCHAR(10) NULL COMMENT '收货确认来源',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_shipments_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_shipments_shipped_by_user_id` FOREIGN KEY (`shipped_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_shipments_1` CHECK (status IN ('SHIPPED','CONFIRMED')),
    CONSTRAINT `ck_shipments_2` CHECK (confirm_source IS NULL OR confirm_source IN ('BUYER','SYSTEM')),
    CONSTRAINT `ck_shipments_3` CHECK ((confirmed_at IS NULL) = (confirm_source IS NULL)),
    CONSTRAINT `ck_shipments_4` CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)),
    CONSTRAINT `ck_shipments_5` CHECK (auto_confirm_at >= shipped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一单一次发货';

CREATE TABLE `skus` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `product_id` BIGINT NOT NULL COMMENT '所属商品ID',
    `option_signature` CHAR(64) NOT NULL COMMENT '规格值组合的SHA-256签名',
    `price` DECIMAL(18,2) NOT NULL COMMENT '销售单价',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '当前可用库存件数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skus_product_id_option_signature` (`product_id`,`option_signature`),
    CONSTRAINT `fk_skus_product_id` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_skus_1` CHECK (price > 0),
    CONSTRAINT `ck_skus_2` CHECK (stock >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可售规格与库存余额';

CREATE TABLE `cart_items` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',
    `sku_id` BIGINT NOT NULL COMMENT '所属SKU ID',
    `quantity` INT NOT NULL COMMENT '商品件数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cart_items_user_id_sku_id` (`user_id`,`sku_id`),
    CONSTRAINT `fk_cart_items_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_cart_items_sku_id` FOREIGN KEY (`sku_id`) REFERENCES `skus` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_cart_items_1` CHECK (quantity BETWEEN 1 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

CREATE TABLE `order_items` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `sku_id` BIGINT NOT NULL COMMENT '所属SKU ID',
    `product_name` VARCHAR(100) NOT NULL COMMENT '下单时商品名称快照',
    `sku_spec` VARCHAR(255) NOT NULL COMMENT '下单时SKU规格快照',
    `quantity` INT NOT NULL COMMENT '商品件数',
    `unit_price` DECIMAL(18,2) NOT NULL COMMENT '下单时商品单价快照',
    `line_amount` DECIMAL(18,2) GENERATED ALWAYS AS (quantity * unit_price) STORED COMMENT '明细金额，数量乘以单价',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_order_items_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_items_sku_id` FOREIGN KEY (`sku_id`) REFERENCES `skus` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_order_items_1` CHECK (quantity > 0),
    CONSTRAINT `ck_order_items_2` CHECK (unit_price > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交商品快照';

CREATE TABLE `payment_reconciliation_issues` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `order_id` BIGINT NOT NULL COMMENT '所属订单ID',
    `payment_attempt_id` BIGINT NULL COMMENT '关联支付尝试ID',
    `issue_type` VARCHAR(32) NOT NULL COMMENT '支付对账异常类型',
    `status` VARCHAR(12) NOT NULL COMMENT '对账异常处理状态',
    `summary` VARCHAR(500) NOT NULL COMMENT '不含敏感信息的摘要',
    `detected_at` DATETIME NOT NULL COMMENT '异常发现时间',
    `resolved_at` DATETIME NULL COMMENT '异常解决时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_payment_reconciliation_issues_order_id` FOREIGN KEY (`order_id`) REFERENCES `orders` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_payment_reconciliation_issues_payment_attempt_id` FOREIGN KEY (`payment_attempt_id`) REFERENCES `payment_attempts` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_payment_reconciliation_issues_1` CHECK (status IN ('OPEN','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付对账异常';

CREATE TABLE `product_option_values` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `option_id` BIGINT NOT NULL COMMENT '所属规格维度ID',
    `value_name` VARCHAR(50) NOT NULL COMMENT '规格值名称',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示排序值',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_product_option_values_option_id_value_name` (`option_id`,`value_name`),
    CONSTRAINT `fk_product_option_values_option_id` FOREIGN KEY (`option_id`) REFERENCES `product_options` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_product_option_values_1` CHECK (sort_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规格值';

CREATE TABLE `refund_attempts` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `case_id` BIGINT NOT NULL COMMENT '所属售后申请ID',
    `payment_attempt_id` BIGINT NOT NULL COMMENT '关联支付尝试ID',
    `out_request_no` VARCHAR(64) NOT NULL COMMENT '商户退款请求号',
    `provider_refund_no` VARCHAR(64) NULL COMMENT '支付渠道退款单号',
    `amount` DECIMAL(18,2) NOT NULL COMMENT '本次渠道退款请求金额',
    `status` VARCHAR(12) NOT NULL COMMENT '渠道退款尝试状态',
    `active_case_id` BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','UNKNOWN') THEN case_id ELSE NULL END) STORED COMMENT '未决退款售后申请唯一约束生成值',
    `requested_at` DATETIME NOT NULL COMMENT '申请或请求时间',
    `resolved_at` DATETIME NULL COMMENT '异常解决时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_refund_attempts_out_request_no` (`out_request_no`),
    UNIQUE KEY `uk_refund_attempts_provider_refund_no` (`provider_refund_no`),
    UNIQUE KEY `uk_refund_attempts_active_case_id` (`active_case_id`),
    CONSTRAINT `fk_refund_attempts_case_id` FOREIGN KEY (`case_id`) REFERENCES `after_sale_cases` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_refund_attempts_payment_attempt_id` FOREIGN KEY (`payment_attempt_id`) REFERENCES `payment_attempts` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_refund_attempts_1` CHECK (amount > 0),
    CONSTRAINT `ck_refund_attempts_2` CHECK (status IN ('PENDING','UNKNOWN','SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次渠道退款尝试';

CREATE TABLE `return_shipments` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `case_id` BIGINT NOT NULL COMMENT '所属售后申请ID',
    `carrier_name` VARCHAR(80) NOT NULL COMMENT '快递公司名称',
    `tracking_no` VARCHAR(100) NOT NULL COMMENT '快递运单号',
    `sent_at` DATETIME NOT NULL COMMENT '买家寄回时间',
    `received_at` DATETIME NULL COMMENT '商家收到退货时间',
    `received_by_user_id` BIGINT NULL COMMENT '退货签收人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_return_shipments_case_id` FOREIGN KEY (`case_id`) REFERENCES `after_sale_cases` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_return_shipments_received_by_user_id` FOREIGN KEY (`received_by_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_return_shipments_1` CHECK ((received_at IS NULL) = (received_by_user_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='买家退回运单';

CREATE TABLE `after_sale_items` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `case_id` BIGINT NOT NULL COMMENT '所属售后申请ID',
    `order_item_id` BIGINT NOT NULL COMMENT '所属订单明细ID',
    `quantity` INT NOT NULL COMMENT '商品件数',
    `refund_amount` DECIMAL(18,2) NOT NULL COMMENT '该订单明细申请退款金额',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_after_sale_items_case_id_order_item_id` (`case_id`,`order_item_id`),
    CONSTRAINT `fk_after_sale_items_case_id` FOREIGN KEY (`case_id`) REFERENCES `after_sale_cases` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_after_sale_items_order_item_id` FOREIGN KEY (`order_item_id`) REFERENCES `order_items` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_after_sale_items_1` CHECK (quantity > 0),
    CONSTRAINT `ck_after_sale_items_2` CHECK (refund_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按明细和件数退款';

CREATE TABLE `inventory_movements` (
    `id` BIGINT AUTO_INCREMENT COMMENT '主键ID',
    `sku_id` BIGINT NOT NULL COMMENT '所属SKU ID',
    `order_item_id` BIGINT NULL COMMENT '所属订单明细ID',
    `reason` VARCHAR(24) NOT NULL COMMENT '库存变动原因',
    `delta` INT NOT NULL COMMENT '库存变动件数，扣减为负',
    `balance_after` INT NOT NULL COMMENT '变动后的库存余额',
    `operation_key` VARCHAR(128) NOT NULL COMMENT '库存操作幂等业务键',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_inventory_movements_operation_key` (`operation_key`),
    CONSTRAINT `fk_inventory_movements_sku_id` FOREIGN KEY (`sku_id`) REFERENCES `skus` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_inventory_movements_order_item_id` FOREIGN KEY (`order_item_id`) REFERENCES `order_items` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `ck_inventory_movements_1` CHECK (reason IN ('ORDER_DEDUCT','ORDER_CANCEL_RETURN','REFUND_RETURN','MERCHANT_ADJUST')),
    CONSTRAINT `ck_inventory_movements_2` CHECK (delta <> 0),
    CONSTRAINT `ck_inventory_movements_3` CHECK (balance_after >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

CREATE TABLE `sku_option_values` (
    `sku_id` BIGINT NOT NULL COMMENT '所属SKU ID',
    `option_value_id` BIGINT NOT NULL COMMENT '规格值ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`sku_id`,`option_value_id`),
    CONSTRAINT `fk_sku_option_values_sku_id` FOREIGN KEY (`sku_id`) REFERENCES `skus` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_sku_option_values_option_value_id` FOREIGN KEY (`option_value_id`) REFERENCES `product_option_values` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU 的规格值组合';

ALTER TABLE `users` ADD CONSTRAINT `fk_users_avatar_media_id` FOREIGN KEY (`avatar_media_id`) REFERENCES `media_assets` (`id`) ON DELETE RESTRICT;

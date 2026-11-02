ALTER TABLE user_private
    ADD COLUMN phone_lookup_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '手机号 HMAC 查询摘要',
    ADD UNIQUE KEY uk_user_private_phone_lookup_hash (phone_lookup_hash);

CREATE TABLE shop_return_profiles (
    shop_id BIGINT NOT NULL PRIMARY KEY,
    key_version INT NOT NULL,
    contact_name_ciphertext VARBINARY(512) NOT NULL,
    contact_phone_ciphertext VARBINARY(512) NOT NULL,
    consignee_ciphertext VARBINARY(512) NOT NULL,
    phone_ciphertext VARBINARY(512) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail_ciphertext VARBINARY(1024) NOT NULL,
    CONSTRAINT fk_return_profile_shop FOREIGN KEY (shop_id) REFERENCES shops(id)
);

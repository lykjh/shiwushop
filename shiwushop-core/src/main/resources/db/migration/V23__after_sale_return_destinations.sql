CREATE TABLE after_sale_return_destinations (
    case_id BIGINT NOT NULL PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    key_version INT NOT NULL,
    consignee_ciphertext VARBINARY(512) NOT NULL,
    phone_ciphertext VARBINARY(512) NOT NULL,
    province VARCHAR(64) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    detail_ciphertext VARBINARY(1024) NOT NULL,
    CONSTRAINT fk_return_destination_case FOREIGN KEY (case_id) REFERENCES after_sale_cases(id)
);

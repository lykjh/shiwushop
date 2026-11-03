CREATE TABLE payment_bills (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 channel VARCHAR(32) NOT NULL,
 app_id VARCHAR(64) NOT NULL,
 seller_id VARCHAR(64) NOT NULL,
 bill_type VARCHAR(16) NOT NULL,
 bill_date DATE NOT NULL,
 sha256 CHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL,
 storage_key VARCHAR(36) NOT NULL,
 status VARCHAR(32) NOT NULL,
 downloaded_by BIGINT NOT NULL,
 downloaded_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_payment_bill_daily (channel,app_id,seller_id,bill_type,bill_date),
 UNIQUE KEY uk_payment_bill_storage (storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

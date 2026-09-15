-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.
CREATE TABLE IF NOT EXISTS club_wallet_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 adjustment_no VARCHAR(40) NOT NULL,
 admin_id BIGINT NOT NULL, request_id VARCHAR(96) NOT NULL, payload_hash CHAR(32) NOT NULL,
 user_id BIGINT NOT NULL, direction VARCHAR(8) NOT NULL, amount DECIMAL(12,2) NOT NULL,
 reason VARCHAR(200) NOT NULL, result_json LONGTEXT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, completed_at DATETIME NULL,
 UNIQUE KEY uk_wallet_adjustment_no(adjustment_no),
 UNIQUE KEY uk_wallet_adjustment_request(admin_id,request_id),
 KEY idx_wallet_adjustment_user(user_id,created_at),
 CONSTRAINT ck_wallet_adjustment_amount CHECK(amount>0),
 CONSTRAINT ck_wallet_adjustment_direction CHECK(direction IN ('credit','debit'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品奢电竞管理员余额调整单（只追加，不删除）';
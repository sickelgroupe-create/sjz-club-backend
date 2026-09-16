CREATE TABLE IF NOT EXISTS club_sensitive_consent (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 scene VARCHAR(32) NOT NULL,
 notice_version VARCHAR(64) NOT NULL,
 agreed_at DATETIME NOT NULL,
 KEY idx_consent_user (user_id, agreed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

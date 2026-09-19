-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_teen_setting (
  user_id BIGINT NOT NULL,
  pin_hash CHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 0,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='青少年模式监护设置';

CREATE TABLE IF NOT EXISTS club_recharge_tier (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  bonus_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_recharge_tier_amount(amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟充值档位';

CREATE TABLE IF NOT EXISTS club_recharge_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recharge_no VARCHAR(48) NOT NULL,
  user_id BIGINT NOT NULL,
  tier_id BIGINT NULL,
  amount DECIMAL(12,2) NOT NULL,
  bonus_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  credited_amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'created',
  idempotency_key VARCHAR(96) NOT NULL,
  callback_key VARCHAR(96) NULL,
  mock_transaction_no VARCHAR(64) NULL,
  paid_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_recharge_no(recharge_no),
  UNIQUE KEY uk_club_recharge_idem(user_id,idempotency_key),
  UNIQUE KEY uk_club_recharge_callback(user_id,callback_key),
  KEY idx_club_recharge_user(user_id,created_at),
  KEY idx_club_recharge_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟充值订单';

CREATE TABLE IF NOT EXISTS club_customer_config (
  id BIGINT NOT NULL,
  service_name VARCHAR(64) NOT NULL DEFAULT '在线客服',
  icon VARCHAR(64) NOT NULL DEFAULT 'customer-cartoon',
  online_status VARCHAR(16) NOT NULL DEFAULT 'online',
  contact_text VARCHAR(255) NOT NULL DEFAULT '企业微信 · 微信客服',
  page_url VARCHAR(255) NOT NULL DEFAULT '/pages/service/customer',
  qr_image VARCHAR(500) NOT NULL DEFAULT '',
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服入口配置';
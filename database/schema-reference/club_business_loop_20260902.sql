-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_business_config (
  config_key VARCHAR(64) NOT NULL,
  config_value VARCHAR(255) NOT NULL,
  description VARCHAR(255) DEFAULT '',
  updated_by BIGINT DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='俱乐部业务配置';

CREATE TABLE IF NOT EXISTS club_coupon_issue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  coupon_id BIGINT NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  reserved_count INT NOT NULL DEFAULT 0,
  remaining_count INT NOT NULL DEFAULT 1,
  source VARCHAR(32) NOT NULL DEFAULT 'admin',
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_coupon_issue_idem(idempotency_key),
  KEY idx_club_coupon_issue_user(user_id,status,created_at),
  KEY idx_club_coupon_issue_coupon(coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券发放批次';

CREATE TABLE IF NOT EXISTS club_order_coupon (
  order_id BIGINT NOT NULL,
  coupon_issue_id BIGINT NOT NULL,
  discount_amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'reserved',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(order_id),
  KEY idx_club_order_coupon_issue(coupon_issue_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单优惠券占用与核销';

CREATE TABLE IF NOT EXISTS club_order_settlement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  settlement_no VARCHAR(48) NOT NULL,
  order_id BIGINT NOT NULL,
  provider_user_id BIGINT DEFAULT NULL,
  provider_type VARCHAR(16) NOT NULL,
  gross_amount DECIMAL(12,2) NOT NULL,
  commission_rate DECIMAL(7,4) NOT NULL,
  platform_fee DECIMAL(12,2) NOT NULL,
  provider_income DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'settled',
  settled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reversed_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_settlement_no(settlement_no),
  UNIQUE KEY uk_club_settlement_order(order_id),
  KEY idx_club_settlement_provider(provider_user_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单唯一收益结算';

CREATE TABLE IF NOT EXISTS club_action_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_action_idem(user_id,business_type,idempotency_key),
  KEY idx_club_action_business(business_type,business_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务操作幂等记录';

CREATE TABLE IF NOT EXISTS club_role_status (
  user_id BIGINT NOT NULL,
  role_type VARCHAR(16) NOT NULL,
  service_status VARCHAR(16) NOT NULL DEFAULT 'offline',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪玩商家服务状态';
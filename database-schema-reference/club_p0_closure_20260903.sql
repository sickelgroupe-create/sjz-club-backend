-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_schema_history (
  version_no VARCHAR(32) NOT NULL,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品奢电竞增量迁移记录';

CREATE TABLE IF NOT EXISTS club_player_service (
  id BIGINT NOT NULL AUTO_INCREMENT,
  player_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  sku_id BIGINT DEFAULT NULL,
  sku_scope BIGINT GENERATED ALWAYS AS (COALESCE(sku_id,0)) STORED,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_player_service(player_id,product_id,sku_scope),
  KEY idx_club_player_service_product(product_id,sku_id,status),
  KEY idx_club_player_service_player(player_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪玩可承接商品规格关系';

CREATE TABLE IF NOT EXISTS club_aftersale (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aftersale_no VARCHAR(48) NOT NULL,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  provider_user_id BIGINT DEFAULT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'applied',
  reason VARCHAR(64) NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  evidence_json TEXT,
  refund_amount DECIMAL(12,2) NOT NULL,
  provider_note VARCHAR(1000) NOT NULL DEFAULT '',
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewed_by BIGINT DEFAULT NULL,
  request_id VARCHAR(96) NOT NULL,
  refund_idempotency_key VARCHAR(96) DEFAULT NULL,
  review_request_id VARCHAR(96) DEFAULT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  provider_processed_at DATETIME DEFAULT NULL,
  reviewed_at DATETIME DEFAULT NULL,
  refunded_at DATETIME DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_aftersale_no(aftersale_no),
  UNIQUE KEY uk_club_aftersale_order(order_id),
  UNIQUE KEY uk_club_aftersale_request(user_id,request_id),
  UNIQUE KEY uk_club_aftersale_review_request(review_request_id),
  KEY idx_club_aftersale_status(status,applied_at),
  KEY idx_club_aftersale_provider(provider_user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单售后申请';

CREATE TABLE IF NOT EXISTS club_aftersale_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  aftersale_id BIGINT NOT NULL,
  from_status VARCHAR(32) NOT NULL DEFAULT '',
  to_status VARCHAR(32) NOT NULL,
  operator_type VARCHAR(20) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  note VARCHAR(1000) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_club_aftersale_log(aftersale_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后状态日志';

CREATE TABLE IF NOT EXISTS club_provider_receivable (
  id BIGINT NOT NULL AUTO_INCREMENT,
  receivable_no VARCHAR(48) NOT NULL,
  provider_user_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  aftersale_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'outstanding',
  recovered_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_receivable_aftersale(aftersale_id),
  UNIQUE KEY uk_club_receivable_no(receivable_no),
  KEY idx_club_receivable_provider(provider_user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务方退款追偿应收';

CREATE TABLE IF NOT EXISTS club_admin_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_id BIGINT NOT NULL,
  permission_code VARCHAR(64) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  request_id VARCHAR(96) NOT NULL DEFAULT '',
  before_json LONGTEXT,
  after_json LONGTEXT,
  reason VARCHAR(1000) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  KEY idx_club_admin_audit_entity(entity_type,entity_id,created_at),
  KEY idx_club_admin_audit_admin(admin_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Club后台高风险操作审计';

CREATE TABLE IF NOT EXISTS club_account_merge_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_no VARCHAR(48) NOT NULL,
  target_user_id BIGINT NOT NULL,
  source_user_id BIGINT NOT NULL,
  conflict_type VARCHAR(20) NOT NULL,
  preview_json LONGTEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'previewed',
  verified_at DATETIME DEFAULT NULL,
  merged_at DATETIME DEFAULT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_merge_request_no(request_no),
  KEY idx_club_merge_users(target_user_id,source_user_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机号与微信账号受控合并请求';
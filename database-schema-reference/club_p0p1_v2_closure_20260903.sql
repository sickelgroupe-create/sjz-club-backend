-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_schema_history (
  version_no VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  checksum VARCHAR(64) NOT NULL DEFAULT '',
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  installed_by VARCHAR(64) NOT NULL DEFAULT 'system',
  success TINYINT NOT NULL DEFAULT 1,
  execution_time_ms BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY(version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品奢电竞增量迁移记录';

CREATE TABLE IF NOT EXISTS club_provider_receivable_recovery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recovery_no VARCHAR(48) NOT NULL,
  receivable_id BIGINT NOT NULL,
  provider_user_id BIGINT NOT NULL,
  settlement_id BIGINT DEFAULT NULL,
  order_id BIGINT NOT NULL,
  aftersale_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  recovery_type VARCHAR(20) NOT NULL DEFAULT 'income_offset',
  operator_id BIGINT DEFAULT NULL,
  reason VARCHAR(500) NOT NULL DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_receivable_recovery_no(recovery_no),
  KEY idx_club_receivable_recovery_receivable(receivable_id,created_at),
  KEY idx_club_receivable_recovery_provider(provider_user_id,created_at),
  CONSTRAINT fk_club_recovery_receivable FOREIGN KEY(receivable_id) REFERENCES club_provider_receivable(id),
  CONSTRAINT fk_club_recovery_provider FOREIGN KEY(provider_user_id) REFERENCES club_user(id),
  CONSTRAINT fk_club_recovery_order FOREIGN KEY(order_id) REFERENCES club_order(id),
  CONSTRAINT fk_club_recovery_aftersale FOREIGN KEY(aftersale_id) REFERENCES club_aftersale(id),
  CONSTRAINT ck_club_recovery_amount CHECK(amount>0),
  CONSTRAINT ck_club_recovery_type CHECK(recovery_type IN ('income_offset','manual_writeoff'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务方追偿逐笔回收明细';

CREATE TABLE IF NOT EXISTS club_teen_daily_spend (
  user_id BIGINT NOT NULL,
  spend_date DATE NOT NULL,
  used_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,spend_date),
  CONSTRAINT fk_club_teen_daily_user FOREIGN KEY(user_id) REFERENCES club_user(id),
  CONSTRAINT ck_club_teen_daily_amount CHECK(used_amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='青少年每日消费并发控制账';

CREATE TABLE IF NOT EXISTS club_admin_action_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  admin_user_id BIGINT NOT NULL,
  entity_type VARCHAR(48) NOT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  result_json LONGTEXT DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME DEFAULT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_admin_action_request(admin_user_id,entity_type,idempotency_key),
  KEY idx_club_admin_action_created(created_at),
  CONSTRAINT fk_club_admin_action_user FOREIGN KEY(admin_user_id) REFERENCES sys_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台新增与配置写入幂等请求';
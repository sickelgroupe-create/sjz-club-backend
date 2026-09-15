-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.
CREATE TABLE IF NOT EXISTS club_virtual_goods (
  sku_id BIGINT NOT NULL,
  environment TINYINT NOT NULL,
  goods_id VARCHAR(20) NOT NULL,
  price_fen INT NOT NULL,
  published TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY(sku_id,environment),
  UNIQUE KEY uk_virtual_goods(environment,goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS club_virtual_payment (
  payment_no VARCHAR(32) NOT NULL PRIMARY KEY,
  environment TINYINT NOT NULL,
  openid VARCHAR(128) NOT NULL,
  goods_id VARCHAR(20) NOT NULL,
  expected_fen INT NOT NULL,
  unit_price_fen INT NOT NULL,
  quantity INT NOT NULL,
  order_type INT NULL,
  remote_status INT NULL,
  remote_order_id VARCHAR(128) NULL,
  delivery_confirmed TINYINT NOT NULL DEFAULT 0,
  refund_no VARCHAR(32) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_virtual_refund(refund_no),
  KEY idx_virtual_reconcile(remote_status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
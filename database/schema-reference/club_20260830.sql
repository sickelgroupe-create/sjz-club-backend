-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_user (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account VARCHAR(64) NOT NULL,
  phone VARCHAR(20) DEFAULT NULL,
  email VARCHAR(128) DEFAULT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar VARCHAR(500) DEFAULT '',
  gender VARCHAR(16) DEFAULT 'unknown',
  birthday DATE DEFAULT NULL,
  city VARCHAR(64) DEFAULT '',
  level_name VARCHAR(32) DEFAULT 'LV.1 初入江湖',
  experience INT NOT NULL DEFAULT 0,
  user_type VARCHAR(16) NOT NULL DEFAULT 'user',
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  last_login_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_user_account (account),
  UNIQUE KEY uk_club_user_phone (phone),
  UNIQUE KEY uk_club_user_email (email),
  KEY idx_club_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序独立账号';

CREATE TABLE IF NOT EXISTS club_user_token (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  token_type VARCHAR(16) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_club_token_hash (token_hash),
  KEY idx_club_token_user (user_id, token_type, revoked_at),
  CONSTRAINT fk_club_token_user FOREIGN KEY (user_id) REFERENCES club_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序访问和刷新令牌';

CREATE TABLE IF NOT EXISTS club_shop (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  logo VARCHAR(500) DEFAULT '',
  description VARCHAR(1000) DEFAULT '',
  owner_user_id BIGINT DEFAULT NULL,
  fans_count INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_club_shop_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺';

CREATE TABLE IF NOT EXISTS club_category (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(50) NOT NULL,
  icon VARCHAR(50) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  PRIMARY KEY (id), UNIQUE KEY uk_club_category_code(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品服务分类';

CREATE TABLE IF NOT EXISTS club_product (
  id BIGINT NOT NULL AUTO_INCREMENT,
  shop_id BIGINT NOT NULL,
  category_code VARCHAR(32) NOT NULL,
  name VARCHAR(120) NOT NULL,
  subtitle VARCHAR(255) DEFAULT '',
  image VARCHAR(500) DEFAULT '',
  detail_text TEXT,
  price DECIMAL(12,2) NOT NULL,
  old_price DECIMAL(12,2) DEFAULT NULL,
  stock INT NOT NULL DEFAULT 0,
  sales INT NOT NULL DEFAULT 0,
  views INT NOT NULL DEFAULT 0,
  is_hot TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_club_product_filter(category_code,status),
  CONSTRAINT fk_club_product_shop FOREIGN KEY (shop_id) REFERENCES club_shop(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品和服务';

CREATE TABLE IF NOT EXISTS club_product_sku (
  id BIGINT NOT NULL AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  stock INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  PRIMARY KEY (id), KEY idx_club_sku_product(product_id,status),
  CONSTRAINT fk_club_sku_product FOREIGN KEY (product_id) REFERENCES club_product(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品规格';

CREATE TABLE IF NOT EXISTS club_player_profile (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT DEFAULT NULL,
  display_name VARCHAR(64) NOT NULL,
  gender VARCHAR(16) NOT NULL DEFAULT 'unknown',
  age INT DEFAULT NULL,
  city VARCHAR(64) DEFAULT '',
  intro VARCHAR(255) DEFAULT '',
  image VARCHAR(500) DEFAULT '',
  voice_url VARCHAR(500) DEFAULT '',
  voice_seconds INT NOT NULL DEFAULT 0,
  online_status TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  sort_no INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), UNIQUE KEY uk_club_player_user(user_id), KEY idx_club_player_filter(gender,status,online_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='陪玩人员公开资料';

CREATE TABLE IF NOT EXISTS club_content (
  id BIGINT NOT NULL AUTO_INCREMENT,
  content_type VARCHAR(32) NOT NULL,
  scene VARCHAR(32) NOT NULL DEFAULT '',
  title VARCHAR(200) NOT NULL,
  subtitle VARCHAR(255) DEFAULT '',
  image VARCHAR(500) DEFAULT '',
  body_text LONGTEXT,
  target_url VARCHAR(500) DEFAULT '',
  style_json TEXT,
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), KEY idx_club_content_scene(content_type,scene,status,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台可替换的宣传图片、字体、资讯和文档';

CREATE TABLE IF NOT EXISTS club_order (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  sku_id BIGINT NOT NULL,
  player_id BIGINT DEFAULT NULL,
  product_name VARCHAR(120) NOT NULL,
  sku_name VARCHAR(100) NOT NULL,
  product_image VARCHAR(500) DEFAULT '',
  quantity INT NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  total_amount DECIMAL(12,2) NOT NULL,
  game_id VARCHAR(100) DEFAULT '',
  game_nickname VARCHAR(100) DEFAULT '',
  contact VARCHAR(100) DEFAULT '',
  remark VARCHAR(500) DEFAULT '',
  status VARCHAR(20) NOT NULL DEFAULT 'unpaid',
  paid_at DATETIME DEFAULT NULL,
  cancelled_at DATETIME DEFAULT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_club_order_no(order_no),
  KEY idx_club_order_user(user_id,status,created_at), KEY idx_club_order_status(status,created_at),
  CONSTRAINT fk_club_order_user FOREIGN KEY (user_id) REFERENCES club_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

CREATE TABLE IF NOT EXISTS club_order_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  order_id BIGINT NOT NULL,
  from_status VARCHAR(20) DEFAULT '',
  to_status VARCHAR(20) NOT NULL,
  operator_type VARCHAR(20) NOT NULL,
  operator_id BIGINT DEFAULT NULL,
  note VARCHAR(500) DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_order_log(order_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单状态审计';

CREATE TABLE IF NOT EXISTS club_payment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_no VARCHAR(40) NOT NULL,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  mode VARCHAR(16) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'created',
  idempotency_key VARCHAR(80) NOT NULL,
  mock_transaction_no VARCHAR(64) DEFAULT NULL,
  prepay_id VARCHAR(128) DEFAULT NULL,
  stock_reserved TINYINT NOT NULL DEFAULT 0,
  refund_no VARCHAR(64) DEFAULT NULL,
  wechat_refund_id VARCHAR(64) DEFAULT NULL,
  refund_status VARCHAR(20) DEFAULT NULL,
  paid_at DATETIME DEFAULT NULL,
  refunded_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_payment_no(payment_no),
  UNIQUE KEY uk_club_payment_transaction(mock_transaction_no),
  UNIQUE KEY uk_club_payment_refund_no(refund_no),
  UNIQUE KEY uk_club_payment_idem(user_id,order_id,idempotency_key),
  KEY idx_club_payment_order(order_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单支付单';

CREATE TABLE IF NOT EXISTS club_payment_audit (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  result_status VARCHAR(20) NOT NULL,
  request_id VARCHAR(80) DEFAULT '',
  detail VARCHAR(500) DEFAULT '',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_payment_audit(payment_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟支付审计';

CREATE TABLE IF NOT EXISTS club_favorite (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_favorite(user_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品收藏';

CREATE TABLE IF NOT EXISTS club_follow (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_follow(user_id,shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺关注';

CREATE TABLE IF NOT EXISTS club_poster (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_poster_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户生成的商品海报记录';

CREATE TABLE IF NOT EXISTS club_coupon (
  id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(100) NOT NULL, amount DECIMAL(12,2) NOT NULL,
  min_spend DECIMAL(12,2) NOT NULL DEFAULT 0, valid_from DATETIME NOT NULL, valid_until DATETIME NOT NULL,
  total_count INT NOT NULL DEFAULT 0, issued_count INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL DEFAULT 'active',
  PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券';

CREATE TABLE IF NOT EXISTS club_user_coupon (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'unused', used_order_id BIGINT DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, used_at DATETIME DEFAULT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_club_user_coupon(user_id,coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券';

CREATE TABLE IF NOT EXISTS club_wallet (
  user_id BIGINT NOT NULL, balance DECIMAL(12,2) NOT NULL DEFAULT 0, frozen DECIMAL(12,2) NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户钱包';

CREATE TABLE IF NOT EXISTS club_wallet_record (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, record_type VARCHAR(32) NOT NULL,
  amount DECIMAL(12,2) NOT NULL, balance_after DECIMAL(12,2) NOT NULL, reference_no VARCHAR(64) DEFAULT '',
  description VARCHAR(255) DEFAULT '', created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_wallet_record(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包流水';

CREATE TABLE IF NOT EXISTS club_withdrawal (
  id BIGINT NOT NULL AUTO_INCREMENT, withdrawal_no VARCHAR(40) NOT NULL, user_id BIGINT NOT NULL,
  amount DECIMAL(12,2) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending', review_note VARCHAR(500) DEFAULT '',
  reviewed_by BIGINT DEFAULT NULL, reviewed_at DATETIME DEFAULT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_withdrawal_no(withdrawal_no), KEY idx_club_withdrawal(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现申请';

CREATE TABLE IF NOT EXISTS club_message (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, message_type VARCHAR(32) NOT NULL,
  title VARCHAR(150) NOT NULL, content VARCHAR(1000) NOT NULL, reference_type VARCHAR(32) DEFAULT '',
  reference_id BIGINT DEFAULT NULL, is_read TINYINT NOT NULL DEFAULT 0, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_message(user_id,is_read,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户消息';

CREATE TABLE IF NOT EXISTS club_review (
  id BIGINT NOT NULL AUTO_INCREMENT, order_id BIGINT NOT NULL, product_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
  rating INT NOT NULL, content VARCHAR(1000) DEFAULT '', status VARCHAR(16) NOT NULL DEFAULT 'visible',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_review_order(order_id), KEY idx_club_review_product(product_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单评价';

CREATE TABLE IF NOT EXISTS club_application (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, application_type VARCHAR(20) NOT NULL,
  display_name VARCHAR(100) DEFAULT '', real_name VARCHAR(64) NOT NULL, phone VARCHAR(20) NOT NULL,
  remark VARCHAR(1000) DEFAULT '', invite_code VARCHAR(64) DEFAULT '', agreement_version VARCHAR(64) DEFAULT NULL,
  agreed_at DATETIME DEFAULT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending',
  review_note VARCHAR(500) DEFAULT '', reviewed_by BIGINT DEFAULT NULL, reviewed_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_club_application(status,application_type,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打手和商家入驻';

CREATE TABLE IF NOT EXISTS club_experience_record (
  id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, order_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL, delta INT NOT NULL, before_experience INT NOT NULL,
  after_experience INT NOT NULL, rule_version VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_club_experience_order_event(order_id,event_type),
  KEY idx_club_experience_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户经验获得与退款冲销流水';

CREATE TABLE IF NOT EXISTS club_identity (
  user_id BIGINT NOT NULL, real_name_mask VARCHAR(64) NOT NULL, id_no_mask VARCHAR(32) NOT NULL,
  id_no_hash CHAR(64) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'pending', review_note VARCHAR(500) DEFAULT '',
  submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at DATETIME DEFAULT NULL,
  PRIMARY KEY(user_id), UNIQUE KEY uk_club_identity_hash(id_no_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实名认证脱敏记录';

CREATE TABLE IF NOT EXISTS club_notification_setting (
  user_id BIGINT NOT NULL, order_enabled TINYINT NOT NULL DEFAULT 1, promotion_enabled TINYINT NOT NULL DEFAULT 0,
  system_enabled TINYINT NOT NULL DEFAULT 1, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知设置';
-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_phone_code (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  expires_at DATETIME NOT NULL,
  used_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_phone_code_request(request_id),
  KEY idx_club_phone_code_phone(phone,purpose,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟短信验证码服务端状态';

CREATE TABLE IF NOT EXISTS club_wechat_identity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  openid VARCHAR(64) NOT NULL,
  unionid VARCHAR(64) DEFAULT NULL,
  session_key_hash CHAR(64) NOT NULL,
  last_login_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_wechat_user(user_id),
  UNIQUE KEY uk_club_wechat_openid(openid),
  UNIQUE KEY uk_club_wechat_unionid(unionid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信小程序身份绑定';
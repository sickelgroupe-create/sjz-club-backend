-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_schema_history (
  version_no VARCHAR(32) NOT NULL,
  description VARCHAR(255) NOT NULL,
  installed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品奢电竞增量迁移记录';

CREATE TABLE IF NOT EXISTS club_home_entry (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entry_code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  icon VARCHAR(100) NOT NULL DEFAULT '',
  avatar_image VARCHAR(500) NOT NULL DEFAULT '',
  target_url VARCHAR(500) NOT NULL,
  role_scope VARCHAR(20) NOT NULL DEFAULT 'all',
  sort_no INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_home_entry_code(entry_code),
  KEY idx_club_home_entry_status(status,sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首页功能入口（与商品分类独立）';
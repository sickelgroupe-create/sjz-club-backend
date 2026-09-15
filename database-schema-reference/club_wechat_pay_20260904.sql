-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.
CREATE TABLE IF NOT EXISTS club_schema_history (
  version_no VARCHAR(64) NOT NULL PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
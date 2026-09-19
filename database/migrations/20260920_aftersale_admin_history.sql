-- Existing installations only. Stop the application and back up before upgrading.
-- Execute once before starting the updated backend. Fresh installs use 001 + 002.
-- Existing scalar review_request_id records are archived under the order/case lock
-- before the first new review or reopening. No old review key is reset beforehand.
CREATE TABLE club_aftersale_admin_request (
  request_id varchar(96) NOT NULL,
  aftersale_id bigint NOT NULL,
  admin_user_id bigint DEFAULT NULL,
  action varchar(16) NOT NULL,
  payload_hash char(64) NOT NULL,
  before_json longtext NOT NULL,
  result_json longtext,
  created_at datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at datetime DEFAULT NULL,
  PRIMARY KEY (request_id),
  KEY idx_club_aftersale_admin_history (aftersale_id,created_at),
  CONSTRAINT fk_club_aftersale_admin_case FOREIGN KEY (aftersale_id) REFERENCES club_aftersale (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后管理员审核重开幂等历史';

SHOW INDEX FROM club_aftersale_admin_request;

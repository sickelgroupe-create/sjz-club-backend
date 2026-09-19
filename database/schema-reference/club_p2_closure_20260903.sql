-- SCHEMA REFERENCE ONLY: no business data; not a complete ordered migration.

CREATE TABLE IF NOT EXISTS club_experience_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  delta INT NOT NULL,
  before_experience INT NOT NULL,
  after_experience INT NOT NULL,
  rule_version VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_club_experience_order_event(order_id,event_type),
  KEY idx_club_experience_user(user_id,created_at),
  CONSTRAINT fk_club_experience_user FOREIGN KEY(user_id) REFERENCES club_user(id),
  CONSTRAINT fk_club_experience_order FOREIGN KEY(order_id) REFERENCES club_order(id),
  CONSTRAINT ck_club_experience_values CHECK(before_experience>=0 AND after_experience>=0),
  CONSTRAINT ck_club_experience_event CHECK(event_type IN ('order_completed','order_refunded'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户经验获得与退款冲销流水';
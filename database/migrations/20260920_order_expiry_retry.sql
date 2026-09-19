-- Existing installations only. Stop the application and back up before upgrading.
-- Execute once before starting the updated backend. Fresh installs use 001 + 002.
ALTER TABLE club_order
  ADD COLUMN expiry_checked_at DATETIME(6) NULL AFTER payment_expires_at,
  ADD INDEX idx_club_order_expiry_retry (status, expiry_checked_at, id);

SHOW COLUMNS FROM club_order LIKE 'expiry_checked_at';
SHOW INDEX FROM club_order WHERE Key_name='idx_club_order_expiry_retry';

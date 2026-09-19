-- Existing installations only. Stop the application and back up before upgrading.
-- Execute once before starting the updated backend. Fresh installs use 001 + 002.
ALTER TABLE club_payment
  ADD COLUMN refund_checked_at DATETIME(6) NULL AFTER refund_no,
  ADD INDEX idx_club_payment_refund_retry (mode, status, refund_status, refund_checked_at, id);

SHOW COLUMNS FROM club_payment LIKE 'refund_checked_at';
SHOW INDEX FROM club_payment WHERE Key_name='idx_club_payment_refund_retry';

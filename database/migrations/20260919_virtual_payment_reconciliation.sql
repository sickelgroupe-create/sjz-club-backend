-- Existing installations only. Back up the database and stop the application first.
-- Applies to club_virtual_payment from 20260912 through backend 3225992.
-- Execute ONCE before starting the updated backend. New installs use 001 + 002 instead.
ALTER TABLE club_virtual_payment
  ADD COLUMN payment_checked_at DATETIME(6) NULL,
  ADD COLUMN refund_checked_at DATETIME(6) NULL,
  ADD INDEX idx_virtual_payment_check (payment_checked_at, payment_no),
  ADD INDEX idx_virtual_refund_check (refund_checked_at, payment_no);

-- Verification: both timestamp columns and indexes must be present.
SHOW COLUMNS FROM club_virtual_payment;
SHOW INDEX FROM club_virtual_payment;

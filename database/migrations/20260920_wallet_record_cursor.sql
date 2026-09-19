-- Existing installations: back up before running. Read-only API support, no business data changes.
-- Repeat-safe. Fresh installations already include this index in 001-schema.sql.
SET @wallet_cursor_index_exists = (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'club_wallet_record'
    AND index_name = 'idx_club_wallet_record_cursor'
);
SET @wallet_cursor_index_sql = IF(@wallet_cursor_index_exists = 0,
  'ALTER TABLE club_wallet_record ADD INDEX idx_club_wallet_record_cursor (user_id, id)',
  'SELECT ''wallet cursor index already exists'' AS migration_status');
PREPARE wallet_cursor_index_stmt FROM @wallet_cursor_index_sql;
EXECUTE wallet_cursor_index_stmt;
DEALLOCATE PREPARE wallet_cursor_index_stmt;
SHOW INDEX FROM club_wallet_record WHERE Key_name = 'idx_club_wallet_record_cursor';

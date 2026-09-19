-- READ ONLY. Run against the intended database after backup; investigate every row.
-- Results are exceptions to investigate, NOT authorization to overwrite balances/statuses.
SELECT DATABASE() AS inspected_database, VERSION() AS database_version;

-- Older sequential-assignment bug could mark a partially recovered debt as settled.
SELECT id, order_id, amount, recovered_amount, status
FROM club_provider_receivable
WHERE (status='recovered' AND recovered_amount<amount)
   OR (status='outstanding' AND recovered_amount=amount)
   OR recovered_amount>amount OR recovered_amount<0;

-- Legacy remote-refund/local-timeout cases require merchant-side reconciliation.
SELECT p.id, p.order_id, p.payment_no, p.status, p.refund_no, p.refund_status, a.status AS aftersale_status
FROM club_payment p JOIN club_aftersale a ON a.order_id=p.order_id
WHERE p.mode='wechat' AND p.status='success' AND a.status='approved'
  AND (p.refund_no IS NULL OR p.refund_status IS NULL);

-- Ledger snapshots should agree, but investigate historical import/adjustment policy first.
SELECT w.user_id, w.balance, w.frozen, r.balance_after, r.frozen_after, r.id AS latest_record
FROM club_wallet w JOIN club_wallet_record r ON r.user_id=w.user_id
AND r.id=(SELECT MAX(last_record.id) FROM club_wallet_record last_record WHERE last_record.user_id=w.user_id)
WHERE w.balance<>r.balance_after OR w.frozen<>r.frozen_after;

-- Rejected cases on unfinished orders require an operational decision.
-- New evidence can use the authorized admin reopening workflow; do not edit SQL states.
SELECT a.id, a.order_id, a.status AS aftersale_status, o.status AS order_status
FROM club_aftersale a JOIN club_order o ON o.id=a.order_id
WHERE a.status='rejected' AND o.status IN ('pending','accepted','serving');

-- No passwords, keys, personal identity plaintext, or contact details are selected.

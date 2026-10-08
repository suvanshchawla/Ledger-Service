-- Ledger invariants after a load run. Prints "check|violations"; every violations value must be 0.
-- Meant for a database that only the load run has touched (run.sh starts a fresh one).
SELECT 'journal entries that do not sum to zero', count(*) FROM (
    SELECT journal_entry_id FROM postings GROUP BY journal_entry_id HAVING SUM(amount_minor) <> 0) t
UNION ALL
SELECT 'accounts whose balance differs from the sum of their postings', count(*) FROM (
    SELECT a.id FROM accounts a LEFT JOIN postings p ON p.account_id = a.id
    GROUP BY a.id, a.balance_minor HAVING a.balance_minor <> COALESCE(SUM(p.amount_minor), 0)) t
UNION ALL
SELECT 'customer accounts with a negative balance', count(*) FROM accounts WHERE type = 'CUSTOMER' AND balance_minor < 0
UNION ALL
SELECT 'money not conserved (sum of all balances, must be 0)', ABS(COALESCE(SUM(balance_minor), 0)) FROM accounts
UNION ALL
SELECT 'COMMITTED transfers without exactly one journal entry', count(*) FROM (
    SELECT t.id FROM transfers t LEFT JOIN journal_entries je ON je.transfer_id = t.id
    WHERE t.status = 'COMMITTED' GROUP BY t.id HAVING count(je.id) <> 1) x
UNION ALL
SELECT 'REJECTED transfers that have a journal entry', count(*) FROM transfers t
    JOIN journal_entries je ON je.transfer_id = t.id WHERE t.status = 'REJECTED'
UNION ALL
SELECT 'journal entries without exactly two postings', count(*) FROM (
    SELECT je.id FROM journal_entries je LEFT JOIN postings p ON p.journal_entry_id = je.id
    GROUP BY je.id HAVING count(p.id) <> 2) t
UNION ALL
SELECT 'COMMITTED transfers without exactly one outbox event', count(*) FROM (
    SELECT t.id FROM transfers t LEFT JOIN outbox_events o ON o.aggregate_id = t.id
    WHERE t.status = 'COMMITTED' GROUP BY t.id HAVING count(o.id) <> 1) x
UNION ALL
SELECT 'outbox events for transfers that are not COMMITTED', count(*) FROM outbox_events o
    WHERE NOT EXISTS (SELECT 1 FROM transfers t WHERE t.id = o.aggregate_id AND t.status = 'COMMITTED')
UNION ALL
SELECT 'deadlocks detected by PostgreSQL', deadlocks FROM pg_stat_database WHERE datname = current_database();

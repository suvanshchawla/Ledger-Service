-- The platform's system account: source of deposits, so its balance goes negative
-- FIXED id, referenced in code as SystemsAccountId.TREASURY_ID
INSERT INTO accounts (id, type, currency, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'CAD', 'Treasury');

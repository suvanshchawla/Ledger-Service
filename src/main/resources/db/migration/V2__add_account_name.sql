-- Account display name. Not unique: two customers can share a name.
-- Assumes accounts is empty; on a non-empty table this fails, which is what we want.
ALTER TABLE accounts ADD COLUMN name TEXT NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_name_trimmed CHECK (name !~ '^\s|\s$'),
    ADD CONSTRAINT accounts_name_length  CHECK (char_length(name) BETWEEN 1 AND 100);
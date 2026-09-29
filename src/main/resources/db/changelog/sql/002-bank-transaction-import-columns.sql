-- Bank transaction import handling (Milestone 2, bullet 4) - client feedback 2026-09-13:
--
-- "In transactions table, we should add 2 columns: bank_name and transaction_file where a
--  path to S3 is added."
--
-- bank_name / transaction_file are exactly what the client asked for: bank_name identifies
-- which account/bank a statement belongs to (extracted from the AI response, not the file
-- format itself, per their answer), transaction_file is a plain string S3 path/key - this
-- service only stores whatever path it's given; the actual upload-to-S3 wiring is their
-- internal developer's job per the same message.
--
-- file_md5 is NOT something the client named explicitly, but is required infrastructure for
-- the exact duplicate-detection behavior they described: "we check MD5 to decide if it is
-- duplicated. We reject it in case it is exactly the same file." There has to be somewhere to
-- persist an already-imported file's hash to check the next upload against - this is that.
ALTER TABLE bank_transaction
    ADD COLUMN bank_name VARCHAR(255),
    ADD COLUMN transaction_file VARCHAR(1024),
    ADD COLUMN file_md5 VARCHAR(32);

CREATE INDEX idx_bank_transaction_file_md5 ON bank_transaction (file_md5);

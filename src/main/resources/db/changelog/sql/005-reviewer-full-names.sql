-- Client-supplied 2026-09-28 "Reconciliation Module - API Update Specifications", points 2 and 3:
-- reviewed_by must now be the reviewer's full name (e.g. "Prince Boghara"), not the JWT "sub" id.
--
-- The listing endpoints can't resolve a name at read time - the caller is whoever is viewing the
-- list, not whoever reviewed/reconciled the transaction, and this service has no user directory
-- of its own - so the name is captured from the acting user's own JWT at write time and stored
-- next to the existing id columns. The id columns stay as they were (still the audit key);
-- rows written before this migration simply have no name and fall back to their id when shown.
ALTER TABLE bank_transaction
    ADD COLUMN reviewer_name VARCHAR(255);

ALTER TABLE reconciliation
    ADD COLUMN created_by_name VARCHAR(255);

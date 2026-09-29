-- Milestone 3 REST API layer - client-supplied 2026-09-16 "Reconciliation Feature – Frontend
-- API Requirements" doc, API 8 ("Mark Transaction for Review") backend responsibility #4:
-- "Store the review timestamp." Not part of that API's documented JSON response, but an
-- explicit storage requirement regardless - see BankTransaction.reviewedAt's javadoc.
--
-- NEEDS_REVIEW (BankTransactionStatus, same doc's section 2) needs no migration at all:
-- reconciliation_status is already a plain VARCHAR(32) (see 001-init-schema.sql's own comment
-- on why - "adding a new status later is a zero-downtime application deploy instead of a
-- migration that alters a type"), so it's a Java-only enum addition.
ALTER TABLE bank_transaction
    ADD COLUMN reviewed_at TIMESTAMPTZ;

-- Reconciliation feature v02 schema update - client-supplied 2026-09-15
-- ("Tables_Of_Reconciliation_feature_v02.sql", Gdrive folder
-- 20260915_Milestone2_documents_and_files - both uploaded copies were byte-identical
-- duplicates). Frontend API requirements from the same folder are deliberately NOT applied
-- here - client note: "Ignore the frontend part for now. Not sure whether this will be part
-- of current sprint or not."
--
-- Deliberate departures from the client's literal DDL, and why:
--   1. bank_statement.file_md5 already gets a unique index from its own UNIQUE constraint -
--      the client's SQL also added a second plain index on that same single column, which
--      Postgres would just be maintaining twice for the same lookup. Not created here.
--   2. reconciliation.invoice_ids was specified as
--      "BIGINT[] NOT NULL REFERENCES supplier_documents_record (id)" - Postgres has no such
--      thing as a foreign key on an array column referencing a scalar column (their own SQL
--      wouldn't even execute - a separate `fk_reconciliation_invoice` constraint further down
--      also referenced a non-existent `invoice_id` column on the new table). Array membership
--      can't be declaratively enforced against another table in Postgres, so every invoice id
--      is instead validated at the application layer before the row is written - see
--      ReconciliationService#assertInvoicesExist.
--   3. allocated_amount became allocated_amounts (NUMERIC(19,4)[]), a second array parallel to
--      invoice_ids (invoice_ids[i] <-> allocated_amounts[i]) - the client's SQL kept a single
--      scalar allocated_amount alongside the new invoice_ids array, which would have silently
--      lost the per-invoice split amount for a 1:N/N:N match (client-confirmed 2026-09-13
--      requirement: "The engine auto-suggest that split" - see RuleBasedMatchingEngine). A
--      CHECK constraint keeps the two arrays the same length.
--
-- No data backfill for existing rows: this is still pre-launch dev data (no client-facing
-- deployment has run against this schema yet), so existing bank_transaction.transaction_file/
-- file_md5 values are simply dropped rather than migrated into a synthesized bank_statement
-- row, and the new reconciliation array columns are added NOT NULL with no default (fails
-- loudly, rather than silently, if any pre-existing row were ever found).

-- =====================================================
-- Bank Statements - one row per imported PDF statement file. Never created for a PONTO/MANUAL
-- bank_transaction (see that table's new columns below) - those have no source file at all.
-- =====================================================
CREATE TABLE bank_statement
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID           NOT NULL,
    bank_name        VARCHAR(255),
    transaction_file VARCHAR(1024),
    file_md5         VARCHAR(32)    NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_bank_statement_file_md5 UNIQUE (file_md5)
);

CREATE INDEX idx_bank_statement_account_id ON bank_statement (account_id);

-- =====================================================
-- Bank Transactions - source-agnostic import columns (PDF/PONTO/MANUAL - see
-- be.smobile.reconciliation.model.enums.SourceType) and the reviewer columns for the review
-- workflow. transaction_file/file_md5 move onto bank_statement above (dropping their column
-- also drops 002's idx_bank_transaction_file_md5, which indexed only that column).
-- =====================================================
ALTER TABLE bank_transaction
    DROP COLUMN transaction_file,
    DROP COLUMN file_md5,
    ADD COLUMN statement_id            UUID REFERENCES bank_statement (id),
    ADD COLUMN source_type             VARCHAR(20) NOT NULL DEFAULT 'PDF',
    ADD COLUMN external_transaction_id VARCHAR(255),
    ADD COLUMN reviewer_id             VARCHAR(255),
    ADD COLUMN reviewer_comment        TEXT,
    ADD CONSTRAINT chk_bank_transaction_amount CHECK (amount > 0),
    -- NULL external_transaction_id (every PDF-sourced row) never collides - Postgres treats
    -- every NULL in a UNIQUE constraint as distinct from every other one.
    ADD CONSTRAINT uk_bank_transaction_external_id UNIQUE (account_id, external_transaction_id);

-- The DEFAULT above only exists to satisfy NOT NULL for any pre-existing row (all PDF-sourced,
-- correctly PDF) - new rows always set source_type explicitly, so the default is dropped
-- immediately rather than left to mask a caller that forgets to set it.
ALTER TABLE bank_transaction ALTER COLUMN source_type DROP DEFAULT;

CREATE INDEX idx_bank_transaction_statement_id ON bank_transaction (statement_id);
CREATE INDEX idx_bank_transaction_source_type ON bank_transaction (source_type);
CREATE INDEX idx_bank_transaction_external_id ON bank_transaction (external_transaction_id);

-- =====================================================
-- Reconciliation - invoice_id (single bigint FK) becomes invoice_ids (bigint[]), so one
-- confirmed match against several invoices (1:N/N:N) is one row instead of one row per
-- invoice. allocated_amounts mirrors it 1:1 so the per-invoice split survives (see header).
-- Dropping invoice_id also drops its FK constraint and 001's idx_reconciliation_invoice_id,
-- both of which depended only on that column.
-- =====================================================
ALTER TABLE reconciliation
    DROP COLUMN invoice_id,
    DROP COLUMN allocated_amount,
    ADD COLUMN invoice_ids       BIGINT[]         NOT NULL,
    ADD COLUMN allocated_amounts NUMERIC(19, 4)[] NOT NULL,
    ADD CONSTRAINT chk_reconciliation_invoice_ids_not_empty CHECK (cardinality(invoice_ids) > 0),
    ADD CONSTRAINT chk_reconciliation_amounts_match_invoices CHECK (cardinality(invoice_ids) = cardinality(allocated_amounts));

-- GIN, not btree: queries against this column are array-membership lookups ("which
-- reconciliation rows touch invoice X"), which a btree index on an array column can't serve -
-- GIN is Postgres's own index type for that.
CREATE INDEX idx_reconciliation_invoice_ids ON reconciliation USING GIN (invoice_ids);

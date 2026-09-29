-- Schema for the Reconciliation Module's own tables (design doc section 8): bank_transaction
-- and reconciliation. Deliberately does NOT create an "invoice" table - invoices, expenses and
-- credit/debit notes already live in supplier_documents_record, owned by account-service (see
-- be.smobile.reconciliation.entity.SupplierDocumentsRecord).
--
-- No schema-qualified names (no "moac." prefix, no CREATE SCHEMA): this runs once per tenant
-- schema, with search_path already pointed at that schema by whichever caller applied this
-- changelog - see be.smobile.reconciliation.multitenancy.TenantSchemaMigrationRunner. Tables
-- therefore land in whatever schema is current when this executes, same as
-- supplier_documents_record itself.
--
-- Monetary columns use NUMERIC(19,4). Status/type/direction columns store the Java enum
-- name as text rather than a Postgres ENUM type, so adding a new status later is a
-- zero-downtime application deploy instead of a migration that alters a type.

CREATE TABLE bank_transaction
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID           NOT NULL,
    transaction_date      DATE           NOT NULL,
    description           VARCHAR(255),
    reference             VARCHAR(255),
    amount                NUMERIC(19, 4) NOT NULL,
    direction             VARCHAR(16)    NOT NULL,
    reconciliation_status VARCHAR(32)    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_bank_transaction_account_id ON bank_transaction (account_id);
CREATE INDEX idx_bank_transaction_reconciliation_status ON bank_transaction (reconciliation_status);
CREATE INDEX idx_bank_transaction_transaction_date ON bank_transaction (transaction_date);

-- The allocation table: one row per (bank transaction, invoice) pairing. This is what lets
-- 1:1, 1:N, N:1 and N:N reconciliations (design doc sections 5.1-5.4) share one schema -
-- a bank transaction or invoice can be referenced by any number of rows here.
--
-- invoice_id is BIGINT (not UUID like this table's own id) because it references
-- supplier_documents_record.id, which is a bigint IDENTITY column owned by account-service -
-- the FK type must match what it points to, not this module's own PK convention.
CREATE TABLE reconciliation
(
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_transaction_id UUID           NOT NULL REFERENCES bank_transaction (id),
    invoice_id          BIGINT         NOT NULL REFERENCES supplier_documents_record (id),
    allocated_amount    NUMERIC(19, 4) NOT NULL,
    reconciliation_date TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          VARCHAR(255)   NOT NULL
);

CREATE INDEX idx_reconciliation_bank_transaction_id ON reconciliation (bank_transaction_id);
CREATE INDEX idx_reconciliation_invoice_id ON reconciliation (invoice_id);

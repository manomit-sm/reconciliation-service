-- Defensive bootstrap for supplier_documents_record, NOT this service's table to own.
--
-- account-service owns supplier_documents_record (invoices/expenses/credit notes - see
-- be.smobile.reconciliation.entity.SupplierDocumentsRecord's javadoc) and is responsible for
-- provisioning it in every tenant schema. This changeset exists purely to protect against
-- migration ORDER: 001-init-schema.sql creates reconciliation.invoice_id as a FK to
-- supplier_documents_record(id) - if this service's TenantSchemaMigrationRunner ever reaches a
-- brand-new tenant schema before account-service's own provisioning has run there, that FK
-- creation would fail outright with "relation supplier_documents_record does not exist".
--
-- Every statement here is written to be a no-op if account-service already created this table
-- (in this schema, at any point, before or after this runs) - CREATE ... IF NOT EXISTS, and the
-- primary key is only added if the table doesn't already have one. This changeset should never
-- fight account-service's own migration for ownership of this table's shape; it only ensures
-- the table exists in *some* valid form before 001-init-schema.sql's FK is created.
--
-- DDL matches the client-supplied pg_dump of tenant_9000000002.supplier_documents_record
-- exactly (see supplierDocumentsRecords.sql, outside this project) - NOT re-derived from the
-- SupplierDocumentsRecord.java entity, to avoid this bootstrap silently drifting from the real
-- table shape if the entity mapping ever changes for this service's own (read-only) purposes.
--
-- Revisit removing this entirely once account-service's own tenant-provisioning process is
-- confirmed to reliably run before any other service's migrations for a new tenant.

CREATE SEQUENCE IF NOT EXISTS supplier_documents_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE IF NOT EXISTS supplier_documents_record
(
    id                              BIGINT NOT NULL DEFAULT nextval('supplier_documents_record_id_seq'),
    date                            DATE,
    account_id                      BIGINT,
    mobile_number                   VARCHAR,
    category                        VARCHAR                 NOT NULL,
    amount                          DOUBLE PRECISION,
    currency                        VARCHAR                 NOT NULL,
    local_value                     DOUBLE PRECISION,
    invoice_pdf_file                VARCHAR,
    invoice_xml_file                VARCHAR,
    invoice_csv_file                VARCHAR,
    invoice_csv_file_path           VARCHAR                 NOT NULL,
    status                          SMALLINT                 DEFAULT 1,
    created_at                      TIMESTAMP                DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at                      TIMESTAMP,
    created_date                    TIMESTAMP,
    document_type                   SMALLINT                 DEFAULT 1,
    load_type                       VARCHAR,
    clientid                        BIGINT                   DEFAULT 5,
    client_email                    VARCHAR,
    crdr                            VARCHAR,
    discount                        DOUBLE PRECISION         DEFAULT 0,
    due_date                        DATE,
    invoice_date                    DATE,
    invoice_number                  VARCHAR,
    sub_total                       DOUBLE PRECISION         DEFAULT 0,
    total_due                       DOUBLE PRECISION         DEFAULT 0,
    total_vat                       DOUBLE PRECISION         DEFAULT 0,
    business_name                   VARCHAR,
    business_phone_number           VARCHAR,
    address_line1                   VARCHAR,
    address_line2                   VARCHAR,
    address_line3                   VARCHAR,
    address_line4                   VARCHAR,
    business_email                  VARCHAR,
    accountant_email                VARCHAR,
    first_name                      VARCHAR,
    last_name                       VARCHAR,
    sent_to_client                  SMALLINT                 DEFAULT 0,
    sent_to_client_date_time        TIMESTAMP,
    sent_to_client_message_id       VARCHAR,
    sent_to_accountant              SMALLINT                 DEFAULT 0,
    sent_to_accountant_date_time    TIMESTAMP,
    sent_to_accountant_message_id   VARCHAR,
    destination_type                SMALLINT                 DEFAULT 0,
    paid_status                     INTEGER                  NOT NULL,
    payment_term                    INTEGER                  NOT NULL,
    order_number                    VARCHAR,
    client_currency                 VARCHAR,
    credit_note_description         VARCHAR,
    credit_note_reason              VARCHAR,
    credit_note_reference_invoice   VARCHAR,
    client_name                     VARCHAR,
    category_id                     INTEGER,
    product_type                    TEXT,
    destination_country_iso2        VARCHAR,
    invoice_type                    VARCHAR                  DEFAULT 'CREATEDINVOICE',
    income_service                  VARCHAR,
    signed_pdf_file                 VARCHAR,
    about                           VARCHAR,
    supplier_name                   VARCHAR,
    payment_method                  VARCHAR,
    add_to_postponed_accounting     BOOLEAN                  DEFAULT false NOT NULL,
    description                     VARCHAR,
    reference                       TEXT,
    additional_information_a        VARCHAR,
    additional_information_b        VARCHAR,
    contact_person                  VARCHAR,
    user_identifier                 VARCHAR,
    etag                            VARCHAR,
    supplier_id                     BIGINT,
    original_vat_amount             NUMERIC,
    vat_percentage                  NUMERIC,
    sent_to_accounting_system       BOOLEAN                  DEFAULT false,
    accounting_system_reference     VARCHAR,
    debit_note_reference_invoice    VARCHAR,
    debit_note_reason               VARCHAR,
    debit_note_description          VARCHAR,
    debit_note_supporting_pdf_file  VARCHAR,
    category_type                   VARCHAR(100)             DEFAULT 'VARIABLE',
    peppol_order_id                 VARCHAR(40),
    peppol_status                   VARCHAR(50),
    peppol_status_info              VARCHAR(500),
    peppol_status_updated_at        TIMESTAMP,
    send_transport_type             VARCHAR(50),
    receive_transport_type          VARCHAR(50),
    provider_status                 VARCHAR(50),
    custom_fields                   JSONB,
    peppol_provider                 VARCHAR(100),
    draft_invoice                   BOOLEAN,
    dokapi_external_reference       VARCHAR(255),
    dokapi_sent_event_ulid          VARCHAR(255),
    dokapi_received_event_ulid      VARCHAR(255),
    dokapi_refused                  BOOLEAN
);

ALTER SEQUENCE supplier_documents_record_id_seq OWNED BY supplier_documents_record.id;

-- Only add a primary key if this table doesn't already have one - if account-service created
-- it first (with its own PK, named however it names it), leave that alone entirely.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'supplier_documents_record'::regclass
          AND contype = 'p'
    ) THEN
        ALTER TABLE supplier_documents_record ADD CONSTRAINT supplier_documents_record_pkey PRIMARY KEY (id);
    END IF;
END $$;

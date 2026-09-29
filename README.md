# moac-reconciliation-service

Spring Boot 4 (Java 21) service for the Pilim Reconciliation Module - matches bank
transactions against customer/supplier invoices (1:1, 1:N, N:1, N:N) and tracks employee
expenses without invoices. See `Pilim Reconciliation Module v02.docx` for the full
functional/technical design.

## Milestone 1 scope (current)

This is the first of several planned milestones, and only covers:

1. **Service scaffolding** - project structure, health check, security config, Docker/CI,
   following the same conventions as `moac-doc-gen-service`.
2. **DB schema & entities** - `BankTransaction`, `Reconciliation` (design doc section 8),
   backed by a Liquibase changelog, plus the shared `SupplierDocumentsRecord` entity (see
   below).
3. **Invoice status algorithm** - design doc section 9, implemented in
   `be.smobile.reconciliation.status.InvoiceStatusCalculator` (+ `InvoiceStatusPresenter`
   for the badge label/color shown in section 2's examples).

Not yet implemented: reconciliation REST APIs, the matching/confidence engine (section 7),
the reconciliation workbench endpoints (section 4), employee expense handling (section 6),
and AI-assisted matching (section 10) - these belong to later milestones.

## Multi-tenancy

Per client feedback (2026-09-12): each Pilim account has its own Postgres schema
(`tenant_<accountId>`), not a shared schema. This service uses Hibernate's schema-based
multi-tenancy (`be.smobile.reconciliation.multitenancy`) rather than a fixed schema:

- `TenantContext` / `TenantFilter` - resolve the current request's tenant id from the JWT's
  `accountId` claim (config: `app.multitenancy.tenant-claim` - **not confirmed against the
  real Keycloak token shape**, only a best-effort guess matching the field name already used
  elsewhere, e.g. `AccountDto.accountId`), falling back to an `X-Tenant-Id` header when
  there's no JWT (local/`app.security.enabled=false` only).
- `SchemaMultiTenantConnectionProvider` / `TenantIdentifierResolver` - the Hibernate SPI
  implementations that actually switch each connection's `search_path` to
  `tenant_<accountId>`.
- `TenantSchemaMigrationRunner` - applies this service's Liquibase changelog to every
  `tenant_*` schema at startup (Spring Boot's own single-shot Liquibase run only targets one
  schema, which doesn't fit one-schema-per-tenant - see its class javadoc for the full
  reasoning and known limitations, e.g. it hasn't been confirmed whether an existing
  tenant-provisioning process elsewhere in the platform should own this instead).

`hibernate.ddl-auto` is `none`, not `validate`: Hibernate's schema validator connects once
via a tenant-less connection and validates against whatever schema that lands in, which
doesn't work when there's no single schema all entities live in (confirmed empirically -
see git history). Schema correctness is `TenantSchemaMigrationRunner`'s job instead, applied
per tenant schema.

## Shared entity: SupplierDocumentsRecord

Per the same client feedback, invoices, expenses and credit/debit notes are represented by
one existing entity/table (`supplier_documents_record`), owned by account-service - not a
bespoke `Invoice` entity. `be.smobile.reconciliation.entity.SupplierDocumentsRecord` is a
field-by-field port of the client-supplied reference entity, cross-checked against their
`pg_dump` of the real table; see its class javadoc for the specific, deliberate differences
(exact column types for `ddl-auto: none` correctness, `java.time` instead of `java.sql`
date/time types, a couple of fields kept as plain types rather than guessing at enums this
service doesn't have the real definition of). **This service only reads this table** - never
write to it.

`InvoiceStatusCalculator`/`InvoiceStatusPresenter` take `paidAmount`/`balanceAmount`/`type`
as plain parameters rather than reading them off `SupplierDocumentsRecord` directly, since
that entity has no such columns (intended to be computed by a caller from this module's own
`reconciliation.allocated_amount` rows against `totalDue` - not yet wired to a real query)
and no confirmed customer-vs-supplier rule.

## Database

PostgreSQL, multi-tenant (see above). Connection details live only in `application.yml` /
`application-local.yml` (`spring.datasource.*`) - no `DataSource`/JPA `@Bean` is declared
anywhere; Spring Boot auto-configures both from those properties. Fill in your local
Postgres details in `application-local.yml`.

To exercise this locally against a single test tenant without a real Keycloak token or
account-service data, create a schema matching the `tenant_` prefix with a
`supplier_documents_record` table (see the client-supplied `supplierDocumentsRecords.sql`
for the real DDL), then call any endpoint with an `X-Tenant-Id: <that schema's suffix>`
header.

## Running locally (no Keycloak required)

```shell script
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The app starts on <http://localhost:7074/reconciliation>, e.g.:

```shell script
curl http://localhost:7074/reconciliation/health/status
```

Swagger UI is available at `/reconciliation/swagger-ui.html`.

## Running with Keycloak (production-like)

```shell script
./mvnw spring-boot:run
```

Requires (see `application.yml`):
- `JWT_ISSUER_URI` - Keycloak realm issuer URL used to validate bearer JWTs
- `KC_APP_CLIENT` - Keycloak client id
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` - PostgreSQL connection
- `TENANT_CLAIM` - JWT claim carrying the account id, if `accountId` turns out to be wrong

## Packaging and running the jar

```shell script
./mvnw package
java -jar target/moac-reconciliation-service-1.0.0-SNAPSHOT.jar
```

## Docker

```shell script
docker build -t moac-reconciliation-service .
docker run -i --rm -p 7074:7074 moac-reconciliation-service
```

## Project layout

```
be.smobile                          - application entry point + health check
be.smobile.reconciliation.config    - Spring configuration (security)
be.smobile.reconciliation.entity    - JPA entities (SupplierDocumentsRecord, BankTransaction, Reconciliation)
be.smobile.reconciliation.model.enums - InvoiceStatus, InvoiceType, BadgeColor, TransactionDirection, BankTransactionStatus
be.smobile.reconciliation.multitenancy - schema-per-tenant routing (see "Multi-tenancy" above)
be.smobile.reconciliation.repository - Spring Data JPA repositories
be.smobile.reconciliation.status    - invoice status algorithm + display formatting
db/changelog                        - Liquibase schema changelog (bank_transaction, reconciliation only)
```

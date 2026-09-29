package be.smobile.reconciliation.entity;

import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.SourceType;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A single line from an imported bank/credit-card statement, per section 8 of the design doc.
 * {@code accountId} references the bank account (see section 3 "Banks" menu) that owns this
 * transaction; a company can have several accounts across several banks.
 * <p>
 * <b>Source-agnostic since the client's 2026-09-15 schema update</b>
 * ("Tables_Of_Reconciliation_feature_v02.sql"): this row now represents a transaction "regardless
 * of source" - {@link #sourceType} PDF (this milestone's only actually-implemented import path,
 * see {@code BankTransactionImportService}), PONTO (Open Banking API - schema/entity ready, no
 * ingestion logic built yet, nothing concrete to build against) or MANUAL (future). Only a
 * {@code PDF} transaction has a {@link #statement}; {@code PONTO} instead sets
 * {@link #externalTransactionId} (duplicate-checked via the DB's own
 * {@code uk_bank_transaction_external_id UNIQUE(account_id, external_transaction_id)}
 * constraint, not application code, since Postgres treats every NULL as distinct there - a PDF
 * row's always-null {@code externalTransactionId} never collides).
 * <p>
 * Lives in each account's own schema ({@code tenant_<accountId>}) - see
 * {@code be.smobile.reconciliation.multitenancy} - so there is deliberately no fixed
 * {@code schema} on {@link Table}: which schema this hits is resolved per-request via
 * {@code search_path}, matching {@link SupplierDocumentsRecord}.
 */
@Entity
@Table(name = "bank_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    @Column(name = "description")
    private String description;

    @Column(name = "reference")
    private String reference;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 16)
    private TransactionDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false, length = 32)
    private BankTransactionStatus reconciliationStatus;

    /**
     * Which bank this transaction's statement came from - client feedback 2026-09-13: extracted
     * from the AI-parsed statement response, not guessed from any file-format-specific field
     * (there is no per-bank file parsing in this service - see {@code BankStatementExtraction}).
     * Kept here (not just on {@link BankStatement}) per the client's own 2026-09-15 column list:
     * a {@code PONTO}/{@code MANUAL} transaction has no statement at all, so this is the only
     * place its bank name can live.
     */
    @Column(name = "bank_name")
    private String bankName;

    /**
     * The imported PDF statement this transaction came from - {@code null} for
     * {@code PONTO}/{@code MANUAL} rows, which have no source file (see {@link BankStatement}'s
     * javadoc). File-level fields ({@code transactionFile}/{@code fileMd5}) moved there entirely
     * as of 2026-09-15 - no longer duplicated per-transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "statement_id")
    private BankStatement statement;

    /** PDF (this milestone's only implemented path) / PONTO / MANUAL - see {@link SourceType}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    /**
     * The Ponto/Open Banking API's own id for this transaction - {@code null} for PDF-imported
     * rows. Duplicate-import prevention for this is a DB constraint
     * ({@code UNIQUE(account_id, external_transaction_id)}), not application code - see class
     * javadoc. Not an enum candidate: an opaque id issued by an external system, not a small
     * fixed vocabulary this codebase controls.
     */
    @Column(name = "external_transaction_id")
    private String externalTransactionId;

    /**
     * Id of the Pilim employee reviewing this transaction's reconciliation - client feedback
     * 2026-09-15. Kept as a plain string, not {@link UUID}, deliberately matching
     * {@code Reconciliation.createdBy}'s existing precedent in this same codebase: both are a
     * JWT-derived actor id, and there is no confirmed guarantee that format is always a UUID
     * (Keycloak's own {@code sub} claim isn't necessarily one) - not an enum either, since it
     * identifies one specific employee out of however many exist, not a fixed small set of
     * values.
     */
    @Column(name = "reviewer_id")
    private String reviewerId;

    /**
     * Full name of {@link #reviewerId}, captured from the acting user's own JWT when the review
     * was recorded - client feedback 2026-09-28 ("Replace the reviewed_by user ID with the
     * user's full name"). Stored rather than looked up on read: this service has no user
     * directory, and the caller of a listing is not the reviewer. {@code null} on rows written
     * before this column existed - callers fall back to {@link #reviewerId}.
     */
    @Column(name = "reviewer_name")
    private String reviewerName;

    /** Free-text comment left by {@link #reviewerId} - client feedback 2026-09-15, self-explanatory. */
    @Column(name = "reviewer_comment")
    private String reviewerComment;

    /**
     * When {@link #reviewerId}/{@link #reviewerComment} were last set - "Mark Transaction for
     * Review" (API 8) backend responsibility #4 in the 2026-09-16 "Frontend API Requirements"
     * doc: "Store the review timestamp." Not itself part of that API's documented response body,
     * but explicitly required to be stored regardless.
     */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}

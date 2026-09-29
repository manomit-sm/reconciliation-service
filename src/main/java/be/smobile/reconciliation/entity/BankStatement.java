package be.smobile.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One imported PDF statement file - split out of {@link BankTransaction} per the client's
 * 2026-09-15 schema update ("Tables_Of_Reconciliation_feature_v02.sql"): "We need to support
 * both PDF imports and Ponto/Open Banking API... 1. bank_statement -&gt; stores imported files
 * and duplicate detection (file_md5)." Never created for a {@code PONTO}/{@code MANUAL}
 * {@link BankTransaction} - those have no source file at all, which is exactly why
 * {@code file_md5}/{@code transaction_file} moved off {@code bank_transaction} onto this table
 * instead of just adding a nullable {@code statement_id}: "Keeps file-level duplication separate
 * from transaction-level duplication."
 * <p>
 * Lives in each account's own schema ({@code tenant_<accountId>}), same as
 * {@link BankTransaction}/{@link SupplierDocumentsRecord} - see
 * {@code be.smobile.reconciliation.multitenancy}.
 */
@Entity
@Table(name = "bank_statement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Printed on the statement, read directly from the AI extraction response - see {@code BankStatementExtraction#bankName()}. */
    @Column(name = "bank_name")
    private String bankName;

    /** Path/key to the file in S3 - stored as-is; the actual upload is the client's internal developer's job. */
    @Column(name = "transaction_file", length = 1024)
    private String transactionFile;

    /** MD5 of the file - exact-reupload rejection, see {@code BankTransactionImportService}. */
    @Column(name = "file_md5", nullable = false, length = 32)
    private String fileMd5;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

package be.smobile.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One confirmed match of (part of) a {@link BankTransaction} against one or more
 * {@link SupplierDocumentsRecord} invoices - "the most important table" per section 8 of the
 * design doc. One row per confirm action; a bank transaction can appear in many rows (section
 * 5.3's N:1, several separate payments), and {@link #invoiceIds} itself may hold more than one
 * invoice in a single row (sections 5.2/5.4's 1:N/N:N groupings) - together this is what lets
 * one table represent all four reconciliation scenarios without any schema change.
 * <p>
 * <b>{@code invoice_id} (singular, one-row-per-pairing) became {@link #invoiceIds}
 * (plural, array) 2026-09-15</b>, per the client's own schema update
 * ("Tables_Of_Reconciliation_feature_v02.sql"): "In reconciliation invoice_id : should be an
 * array." {@link #allocatedAmounts} is a second array added alongside it, parallel to
 * {@code invoiceIds} by index (index <i>i</i> of one is the amount allocated to index
 * <i>i</i>'s invoice) - the client's own SQL kept a single scalar {@code allocated_amount}
 * next to the new array, which would have silently discarded the per-invoice split amount for
 * a 1:N/N:N match (client-confirmed 2026-09-13 requirement: "The engine auto-suggest that
 * split" - see {@code RuleBasedMatchingEngine}). The migration adds a
 * {@code CHECK (cardinality(invoice_ids) = cardinality(allocated_amounts))} constraint so the
 * two can never drift apart.
 * <p>
 * {@code invoice_ids} elements are plain {@code bigint} - matching {@code supplier_documents_record.id}
 * (also {@code bigint}, IDENTITY-generated), not this table's own UUID PK convention, since they
 * must match the referenced column's real type. Postgres has no such thing as a foreign key on
 * an array column referencing a scalar column, so - unlike {@link #bankTransaction} - there is
 * deliberately no {@code @ManyToOne}/FK here: every id in {@link #invoiceIds} is validated
 * against {@code supplier_documents_record} at the application layer instead, see
 * {@code ReconciliationService#confirmMatch}.
 * <p>
 * Lives in each account's own schema ({@code tenant_<accountId>}) - see
 * {@code be.smobile.reconciliation.multitenancy} - so there is deliberately no fixed
 * {@code schema} on {@link Table}, matching {@link BankTransaction}/{@link SupplierDocumentsRecord}.
 */
@Entity
@Table(name = "reconciliation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reconciliation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bank_transaction_id", nullable = false)
    private BankTransaction bankTransaction;

    /** {@code SupplierDocumentsRecord.id} values covered by this one confirm action - see class javadoc. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "invoice_ids", nullable = false, columnDefinition = "bigint[]")
    private List<Long> invoiceIds;

    /** Parallel to {@link #invoiceIds} by index - see class javadoc. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allocated_amounts", nullable = false, columnDefinition = "numeric(19,4)[]")
    private List<BigDecimal> allocatedAmounts;

    @Column(name = "reconciliation_date", nullable = false)
    private LocalDateTime reconciliationDate;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /**
     * Full name of {@link #createdBy} - see {@link BankTransaction#getReviewerName()} for why
     * it's captured at write time. {@code null} on rows written before 2026-09-28.
     */
    @Column(name = "created_by_name")
    private String createdByName;
}

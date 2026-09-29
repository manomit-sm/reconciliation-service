package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.Reconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** {@code Reconciliation}'s {@code @Id} is a {@link UUID} (see {@link Reconciliation}), not a {@code Long}. */
public interface ReconciliationRepository extends JpaRepository<Reconciliation, UUID> {

    List<Reconciliation> findByBankTransactionId(UUID bankTransactionId);

    /** Batch form of {@link #findByBankTransactionId} - one query for a whole page of the transactions list. */
    List<Reconciliation> findByBankTransactionIdIn(List<UUID> bankTransactionIds);

    /**
     * Sum of {@code allocatedAmounts} already recorded per invoice, for the given invoice ids -
     * used to compute {@code MatchCandidate.remainingBalance} (totalDue minus this) so a
     * partially-paid invoice (the N:1 "second installment" case, section 5.3) is matched
     * against what's actually still owed, not its original total. One query for the whole
     * candidate batch rather than one per invoice.
     * <p>
     * Native SQL, not JPQL: since {@code invoice_id} became {@code invoice_ids}/{@code
     * allocated_amounts} (parallel arrays, 2026-09-15 - see {@link Reconciliation}'s javadoc),
     * summing per invoice means pairing each row's two arrays up and grouping by element -
     * {@code unnest(a, b)} does exactly that (Postgres zips same-position elements of both
     * arrays into one row per pair), which JPQL has no equivalent for.
     */
    @Query(value = "select pair.invoice_id as invoiceId, sum(pair.amount) as total "
            + "from reconciliation r, unnest(r.invoice_ids, r.allocated_amounts) as pair(invoice_id, amount) "
            + "where pair.invoice_id in (:invoiceIds) group by pair.invoice_id", nativeQuery = true)
    List<InvoiceAllocatedTotal> sumAllocatedAmountsByInvoiceIds(@Param("invoiceIds") List<Long> invoiceIds);
}

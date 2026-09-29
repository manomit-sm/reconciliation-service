package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code BankTransaction}'s {@code @Id} is a {@link UUID} (see {@link BankTransaction}), not a
 * {@code Long}. Extends {@link JpaSpecificationExecutor} for API 3's ("Get Bank Transactions")
 * combinatorial optional filters (year/month/status/search) - see
 * {@code BankTransactionSpecifications}.
 */
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID>, JpaSpecificationExecutor<BankTransaction> {

    /**
     * Already-imported transactions for one bank account whose date falls within a new
     * statement's range - used to flag possibly-overlapping transactions individually rather
     * than silently re-importing or silently rejecting them, per the client's answer: "the UI
     * should show the user that there are overlap date ranges and the user accept or refuse
     * overlapped range one by one."
     */
    List<BankTransaction> findByAccountIdAndTransactionDateBetween(UUID accountId, LocalDate start, LocalDate end);

    /** API 1's ("Get Reconciliation Summary") per-status counts for the selected year/month. */
    long countByTransactionDateBetweenAndReconciliationStatus(LocalDate start, LocalDate end, BankTransactionStatus status);

    /**
     * Every not-yet-settled transaction on one account, regardless of period - the candidate
     * pool {@code MultiPaymentAllocationService} groups by partner to find a "these N payments
     * together cover M of this partner's invoices" suggestion (see that class's javadoc). Not
     * filtered by date range: unlike a single transaction's own matching, a sibling payment from
     * an earlier or later statement is still a legitimate part of the same group.
     */
    List<BankTransaction> findByAccountIdAndReconciliationStatusIn(UUID accountId, List<BankTransactionStatus> statuses);

    /**
     * Still-unclassified transactions in a date range - the pool
     * {@code ReconciliationService#rematchUnmatched} re-runs the matching engine over.
     * <p>
     * Locked {@code FOR UPDATE SKIP LOCKED} (lock timeout {@code -2} is Hibernate's SKIP_LOCKED):
     * the dashboard fires its summary and list calls at the same moment and both trigger a
     * re-match, so without this both could pick up the same transaction and auto-reconcile it
     * twice, double-allocating its invoices. A row another request is already working on is
     * simply skipped - that request is handling it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    List<BankTransaction> findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus status, LocalDate start, LocalDate end);
}

package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Best-effort implementation of the "Multi-payment allocation" flow from the client's
 * 2026-09-19 S-mobile Reconciliation-screen screenshots ("Wholesale Co sent 2 payments covering
 * 3 open invoices. Review the allocation before approving.") - <b>not</b> in the formal API doc.
 * Neither the original nor the "(updated)" 2026-09-16 "Frontend API Requirements" PDF defines an
 * endpoint, request/response shape, or grouping/allocation rule for this: the updated PDF's only
 * trace of it is a half-edited API 4.3 JSON example (a {@code matched_transactions} array that's
 * really invoice data mislabeled {@code "transactionType": "Bank Transaction"}, plus a new
 * {@code payment_invoices} array with a {@code "category": "A"/"B"} field matching the
 * screenshots' payment labels) - read as an accidental/incomplete doc edit, not a deliberate new
 * schema, so it isn't copied literally here. Instead this exposes its own clearly-named
 * {@code multi_payment_allocation} block on the existing API 4 response (see
 * {@code BankTransactionDtoMapper}/{@code BankTransactionDetail}) and one new action endpoint
 * ({@code POST /transactions/{id}/reconcile-group}, see {@code ReconciliationController}) -
 * function, not the doc's own literal (and internally inconsistent) field names.
 * <p>
 * What IS unambiguous from the six screenshots: when a partner has 2+ unreconciled bank
 * transactions ("payments") whose combined total exactly equals the combined open balance of 2+
 * of that partner's invoices, the system should suggest a cross-payment/cross-invoice allocation
 * for the user to review and approve in one action, instead of reconciling each payment one by
 * one. The allocation itself is a standard accounting technique - a FIFO "waterfall": the oldest
 * payment (by {@code transactionDate}, then {@code createdAt} as a tiebreaker for same-day
 * payments - the screenshots' own two payments are both dated "30 Aug") is applied to the
 * oldest-due invoice first, moving to the next invoice only once the
 * current one is fully covered. That's also the only algorithm consistent with the screenshots'
 * own worked numbers: a 600 EUR payment (A) fully covers a 500 EUR invoice then 100 EUR of a
 * second; a 400 EUR payment (B) covers that second invoice's remaining 100 EUR then a third 300
 * EUR invoice in full - {@code A -> INV-030 (500), A -> INV-031 (100), B -> INV-031 (100),
 * B -> INV-032 (300)}, exactly {@link #findGroup}'s output for that input.
 * <p>
 * Deliberately conservative for this first cut: {@link #findGroup} only returns a suggestion
 * when the waterfall accounts for every cent of every payment (matching the screenshots' own
 * "Difference: €0.00") - a near-miss silently falls back to the ordinary single-transaction
 * suggestion ({@link ReconciliationService#suggest}) instead of showing a confusing partial
 * group. Not confirmed with the client: the exact partner-grouping key (here, an exact
 * case-insensitive match on {@code BankTransaction.description}, the same string the screenshots
 * show verbatim as both the transaction row's label and the payment card's name) and the "every
 * cent must reconcile" cutoff.
 */
@Service
@RequiredArgsConstructor
public class MultiPaymentAllocationService {

    private static final List<BankTransactionStatus> GROUPABLE_STATUSES =
            List.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.SUGGESTED_MATCH);

    private final BankTransactionRepository bankTransactionRepository;
    private final ReconciliationCandidateResolver candidateResolver;
    private final ReconciliationService reconciliationService;

    /** Read-only lookup - used both to decide whether to show the panel (API 4) and, recomputed fresh, to approve it ({@link #approveGroup}). */
    @Transactional(readOnly = true)
    public Optional<PaymentGroup> findGroup(BankTransaction transaction) {
        List<BankTransaction> payments = siblingPayments(transaction);
        if (payments.size() < 2) {
            return Optional.empty();
        }
        List<MatchCandidate> invoices = partnerInvoices(transaction);
        if (invoices.size() < 2) {
            return Optional.empty();
        }
        return waterfall(payments, invoices);
    }

    /**
     * "Approve allocation" - reconciles every payment in {@code transactionId}'s current group
     * against its share of the invoices in one call, each payment attributed to the approving
     * user via the same {@link ReconciliationService#confirmMatch} path a single-transaction
     * Approve-match would use. The group is recomputed here rather than trusting a client-passed
     * allocation, so a stale suggestion (an invoice/payment changed since the user last fetched
     * it) fails loudly instead of reconciling numbers that no longer add up.
     */
    @Transactional
    public BankTransaction approveGroup(UUID transactionId) {
        BankTransaction transaction = findTransaction(transactionId);
        PaymentGroup group = findGroup(transaction)
                .orElseThrow(() -> new IllegalStateException(
                        "No multi-payment allocation is currently available for transaction " + transactionId));

        Map<UUID, List<InvoiceAllocation>> allocationsByPayment = group.allocations().stream()
                .collect(Collectors.groupingBy(a -> a.payment().getId(),
                        Collectors.mapping(a -> new InvoiceAllocation(a.invoiceId(), a.amount()), Collectors.toList())));
        allocationsByPayment.forEach(reconciliationService::confirmMatch);

        return findTransaction(transactionId);
    }

    /**
     * "Mark for review" on the whole group, not just one payment - added 2026-09-19 per the
     * client's own mockup showing a single "Mark for review" button on the multi-payment panel.
     * Flags every payment currently in {@code transactionId}'s group as {@code needs_review}
     * with the same comment, via the same {@link ReconciliationService#markForReview} path a
     * single-transaction "Mark for review" would use for one payment. Same staleness handling as
     * {@link #approveGroup}: the group is recomputed here, not trusted from an earlier fetch.
     */
    @Transactional
    public BankTransaction markGroupForReview(UUID transactionId, String reviewerComment) {
        BankTransaction transaction = findTransaction(transactionId);
        PaymentGroup group = findGroup(transaction)
                .orElseThrow(() -> new IllegalStateException(
                        "No multi-payment allocation is currently available for transaction " + transactionId));

        for (BankTransaction payment : group.payments()) {
            reconciliationService.markForReview(payment.getId(), reviewerComment);
        }

        return findTransaction(transactionId);
    }

    private List<BankTransaction> siblingPayments(BankTransaction transaction) {
        String partner = normalize(transaction.getDescription());
        if (partner.isEmpty()) {
            return List.of();
        }
        return bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(transaction.getAccountId(), GROUPABLE_STATUSES).stream()
                .filter(tx -> partner.equals(normalize(tx.getDescription())))
                .sorted(Comparator.comparing(BankTransaction::getTransactionDate)
                        .thenComparing(BankTransaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BankTransaction::getId))
                .toList();
    }

    private List<MatchCandidate> partnerInvoices(BankTransaction transaction) {
        String partner = normalize(transaction.getDescription());
        return candidateResolver.resolveCandidates(transaction).stream()
                .filter(c -> partnerMatches(normalize(c.partnerName()), partner))
                .sorted(Comparator.comparing(MatchCandidate::dueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean partnerMatches(String candidatePartner, String transactionPartner) {
        if (candidatePartner.isEmpty() || transactionPartner.isEmpty()) {
            return false;
        }
        return candidatePartner.equals(transactionPartner)
                || transactionPartner.contains(candidatePartner)
                || candidatePartner.contains(transactionPartner);
    }

    /** See class javadoc for the algorithm and why it only ever returns a fully-consumed-payments result. */
    private Optional<PaymentGroup> waterfall(List<BankTransaction> payments, List<MatchCandidate> invoices) {
        List<PaymentAllocation> allocations = new ArrayList<>();
        int invoiceIndex = 0;
        BigDecimal invoiceRemaining = invoices.get(0).remainingBalance();

        for (BankTransaction payment : payments) {
            BigDecimal paymentRemaining = payment.getAmount().abs();
            while (paymentRemaining.signum() > 0) {
                if (invoiceIndex >= invoices.size()) {
                    return Optional.empty();
                }
                BigDecimal amount = paymentRemaining.min(invoiceRemaining);
                if (amount.signum() > 0) {
                    allocations.add(new PaymentAllocation(payment, invoices.get(invoiceIndex).invoiceId(), amount));
                }
                paymentRemaining = paymentRemaining.subtract(amount);
                invoiceRemaining = invoiceRemaining.subtract(amount);
                if (invoiceRemaining.signum() <= 0) {
                    invoiceIndex++;
                    if (invoiceIndex < invoices.size()) {
                        invoiceRemaining = invoices.get(invoiceIndex).remainingBalance();
                    }
                }
            }
        }

        if (invoiceIndex < 2) {
            return Optional.empty();
        }
        return Optional.of(new PaymentGroup(payments, allocations));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private BankTransaction findTransaction(UUID transactionId) {
        return bankTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Bank transaction " + transactionId + " not found"));
    }

    /** One line of a {@link PaymentGroup}'s allocation table: "allocate {@code amount} of {@code payment} to {@code invoiceId}". */
    public record PaymentAllocation(BankTransaction payment, Long invoiceId, BigDecimal amount) {
    }

    /** A suggested (not yet persisted) cross-payment/cross-invoice allocation - see class javadoc. */
    public record PaymentGroup(List<BankTransaction> payments, List<PaymentAllocation> allocations) {
    }
}

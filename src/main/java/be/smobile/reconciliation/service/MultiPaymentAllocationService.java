package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
    private final MatchingProperties matchingProperties;

    /** Read-only lookup - used both to decide whether to show the panel (API 4) and, recomputed fresh, to approve it ({@link #approveGroup}). */
    @Transactional(readOnly = true)
    public Optional<PaymentGroup> findGroup(BankTransaction transaction) {
        Optional<PaymentGroup> waterfall = findWaterfallGroup(transaction);
        return waterfall.isPresent() ? waterfall : findPaymentsForOneInvoice(transaction);
    }

    private Optional<PaymentGroup> findWaterfallGroup(BankTransaction transaction) {
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
     * Several payments, one invoice: the fewest payments that include {@code transaction}, are
     * all in the same direction and within the date tolerance of one open invoice, and add up to
     * exactly that invoice's balance. Which invoice is tried first: the closest in date to
     * {@code transaction}.
     */
    private Optional<PaymentGroup> findPaymentsForOneInvoice(BankTransaction transaction) {
        if (transaction.getAmount() == null || transaction.getAmount().signum() <= 0) {
            return Optional.empty();
        }
        List<BankTransaction> pool = sameDirectionPayments(transaction.getAccountId(), transaction.getDirection(), GROUPABLE_STATUSES);
        if (pool.size() < 2) {
            return Optional.empty();
        }
        List<MatchCandidate> invoices = candidateResolver.resolveCandidates(transaction).stream()
                .sorted(Comparator.comparingLong((MatchCandidate c) -> c.dateDistanceDays(transaction.getTransactionDate()))
                        .thenComparing(MatchCandidate::invoiceId))
                .toList();
        for (MatchCandidate invoice : invoices) {
            Optional<List<BankTransaction>> payments = paymentsPayingInvoice(invoice, pool, transaction);
            if (payments.isPresent()) {
                return Optional.of(toGroup(payments.get(), invoice));
            }
        }
        return Optional.empty();
    }

    /**
     * Moves still-"Unmatched" payments in {@code [start, end]} to "Suggested" when together they
     * pay one open invoice exactly (see the class javadoc) - the "several payments, one invoice"
     * case, which the one-transaction-at-a-time matching in
     * {@link ReconciliationService#rematchUnmatched} can't see. Each payment is claimed by at most
     * one invoice. They are suggestions only: nothing is reconciled until the group is approved
     * ({@link #approveGroup}), even when each payment would score above the auto-reconcile
     * threshold on its own - a payment that is merely one part of an invoice is exactly the case
     * where a human should look first.
     *
     * @return how many payments were moved
     */
    @Transactional
    public int promoteGroupedPayments(LocalDate start, LocalDate end) {
        List<BankTransaction> unmatched = bankTransactionRepository
                .findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end).stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().signum() > 0)
                .toList();
        if (unmatched.size() < 2) {
            return 0;
        }

        Map<UUID, List<MatchCandidate>> candidatesByTransaction = candidateResolver.resolveCandidates(unmatched);
        Map<String, List<BankTransaction>> buckets = unmatched.stream()
                .collect(Collectors.groupingBy(tx -> tx.getAccountId() + "|" + tx.getDirection(), LinkedHashMap::new, Collectors.toList()));

        int promoted = 0;
        Set<UUID> claimed = new HashSet<>();
        for (List<BankTransaction> bucket : buckets.values()) {
            Map<Long, MatchCandidate> invoices = new TreeMap<>();
            for (BankTransaction tx : bucket) {
                candidatesByTransaction.getOrDefault(tx.getId(), List.of()).forEach(c -> invoices.putIfAbsent(c.invoiceId(), c));
            }
            for (MatchCandidate invoice : invoices.values()) {
                List<BankTransaction> free = bucket.stream().filter(tx -> !claimed.contains(tx.getId())).toList();
                if (free.size() < 2) {
                    break;
                }
                Optional<List<BankTransaction>> payments = paymentsPayingInvoice(invoice, free, null);
                if (payments.isEmpty()) {
                    continue;
                }
                Instant now = Instant.now();
                for (BankTransaction payment : payments.get()) {
                    payment.setReconciliationStatus(BankTransactionStatus.SUGGESTED_MATCH);
                    payment.setUpdatedAt(now);
                    bankTransactionRepository.save(payment);
                    claimed.add(payment.getId());
                    promoted++;
                }
            }
        }
        return promoted;
    }

    /**
     * The fewest payments from {@code pool} - always including {@code mustInclude} when given -
     * whose amounts add up to exactly {@code invoice}'s remaining balance, every one dated within
     * {@code date-tolerance-days} of the invoice's invoice date or due date. At least two payments
     * (a single payment equal to the invoice is an ordinary one-to-one match, found elsewhere).
     */
    private Optional<List<BankTransaction>> paymentsPayingInvoice(MatchCandidate invoice, List<BankTransaction> pool, BankTransaction mustInclude) {
        BigDecimal balance = invoice.remainingBalance();
        if (balance == null || balance.signum() <= 0) {
            return Optional.empty();
        }
        long tolerance = matchingProperties.getDateToleranceDays();
        Comparator<BankTransaction> nearestFirst = Comparator
                .comparingLong((BankTransaction p) -> invoice.dateDistanceDays(p.getTransactionDate()))
                .thenComparing(BankTransaction::getTransactionDate)
                .thenComparing(BankTransaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BankTransaction::getId);
        List<BankTransaction> eligible = pool.stream()
                .filter(p -> p.getAmount().compareTo(balance) <= 0)
                .filter(p -> invoice.dateDistanceDays(p.getTransactionDate()) <= tolerance)
                .sorted(nearestFirst)
                .toList();

        List<BankTransaction> others = eligible.stream()
                .filter(p -> mustInclude == null || !p.getId().equals(mustInclude.getId()))
                .limit(matchingProperties.getMaxCandidatesForGrouping())
                .toList();
        BigDecimal target = balance;
        if (mustInclude != null) {
            if (eligible.stream().noneMatch(p -> p.getId().equals(mustInclude.getId()))) {
                return Optional.empty();
            }
            target = balance.subtract(mustInclude.getAmount());
            if (target.signum() <= 0) {
                return Optional.empty();
            }
        }

        List<Integer> indices = fewestSummingTo(others, target);
        if (indices == null) {
            return Optional.empty();
        }
        List<BankTransaction> group = new ArrayList<>(indices.stream().map(others::get).toList());
        if (mustInclude != null) {
            group.add(mustInclude);
        }
        if (group.size() < 2) {
            return Optional.empty();
        }
        group.sort(Comparator.comparing(BankTransaction::getTransactionDate)
                .thenComparing(BankTransaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BankTransaction::getId));
        return Optional.of(group);
    }

    /** Indices of the fewest {@code payments} whose amounts sum to exactly {@code target} (earliest on a tie); null if none. Exact long arithmetic on scale-4 micros, same as the matching engine. */
    private List<Integer> fewestSummingTo(List<BankTransaction> payments, BigDecimal target) {
        long goal = toMicros(target);
        Map<Long, List<Integer>> reachable = new LinkedHashMap<>();
        reachable.put(0L, List.of());
        for (int i = 0; i < payments.size(); i++) {
            long amount = toMicros(payments.get(i).getAmount());
            if (amount <= 0 || amount > goal) {
                continue;
            }
            Map<Long, List<Integer>> additions = new LinkedHashMap<>();
            for (Map.Entry<Long, List<Integer>> entry : reachable.entrySet()) {
                long sum = entry.getKey() + amount;
                if (sum > goal) {
                    continue;
                }
                List<Integer> current = additions.containsKey(sum) ? additions.get(sum) : reachable.get(sum);
                if (current != null && current.size() <= entry.getValue().size() + 1) {
                    continue;
                }
                List<Integer> indices = new ArrayList<>(entry.getValue());
                indices.add(i);
                additions.put(sum, indices);
            }
            reachable.putAll(additions);
        }
        List<Integer> hit = reachable.get(goal);
        return hit == null || hit.isEmpty() ? null : hit;
    }

    private long toMicros(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    private PaymentGroup toGroup(List<BankTransaction> payments, MatchCandidate invoice) {
        List<PaymentAllocation> allocations = payments.stream()
                .map(payment -> new PaymentAllocation(payment, invoice.invoiceId(), payment.getAmount().abs()))
                .toList();
        return new PaymentGroup(payments, allocations);
    }

    private List<BankTransaction> sameDirectionPayments(UUID accountId, TransactionDirection direction, List<BankTransactionStatus> statuses) {
        return bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, statuses).stream()
                .filter(tx -> tx.getDirection() == direction && tx.getAmount() != null && tx.getAmount().signum() > 0)
                .toList();
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

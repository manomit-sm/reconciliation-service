package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.Reconciliation;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.matching.MatchCriterionType;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.matching.MatchingEngine;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Section 5/8's "engine core": given a {@link BankTransaction}, produce a live
 * {@link MatchSuggestion} (via {@link MatchingEngine} + {@link ReconciliationCandidateResolver})
 * and, once a set of allocations is chosen, persist it as {@link Reconciliation} rows and mark
 * the transaction reconciled - the actual database-level effect of section 5.1-5.4's four
 * scenarios (1:1/1:N/N:1/N:N), all of which are just "one or more allocation rows against one
 * or more invoices" per section 8.
 * <p>
 * <b>Deliberately does not include</b> listing/filtering/searching transactions or a dashboard
 * summary - that's API/presentation shaping ("REST APIs (dashboard, actions)"), Milestone 3
 * scope, not this milestone's "Reconciliation engine core".
 */
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final BankTransactionRepository bankTransactionRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    private final ReconciliationCandidateResolver candidateResolver;
    private final MatchingEngine matchingEngine;
    private final MatchingProperties matchingProperties;

    /**
     * Attributed to {@link Reconciliation#getCreatedBy()} when {@link #autoConfirm} - not a
     * human - is the one confirming the match. Distinct from {@link #currentUser()}'s own
     * unauthenticated-request fallback ({@code "system"}) so the two cases stay
     * distinguishable in the data.
     */
    private static final String AUTO_RECONCILE_USER = "auto-reconciliation-engine";

    /** Display name stored next to {@link #AUTO_RECONCILE_USER} - what the UI shows as "reviewed by" for an automatic reconciliation. */
    private static final String AUTO_RECONCILE_USER_NAME = "Auto-reconciliation";

    /** Live-computes the best suggestion for a transaction. Read-only - persists nothing. */
    @Transactional(readOnly = true)
    public MatchSuggestion suggest(BankTransaction transaction) {
        List<MatchCandidate> candidates = candidateResolver.resolveCandidates(transaction);
        return matchingEngine.suggest(transaction, candidates);
    }

    /**
     * Section 7's confidence bands, collapsed to the 3 statuses this module tracks (see
     * {@link BankTransactionStatus}) - extended 2026-09-19 with a 4th outcome: a score at or
     * above {@link MatchingProperties#getAutoReconcileThreshold()} is confident enough to skip
     * {@code suggested_match} (and its manual Approve-match step) entirely and go straight to
     * {@code reconciled}. Classifying this doesn't itself persist the {@link Reconciliation} row
     * that {@code reconciled} implies - a caller getting {@link BankTransactionStatus#RECONCILED}
     * back from this method must also call {@link #autoConfirm} with the same suggestion's
     * allocations (see {@code BankTransactionImportService#persist}), the same way a caller
     * getting {@code suggested_match} must eventually call {@link #confirmMatch} itself.
     */
    public BankTransactionStatus classify(MatchSuggestion suggestion) {
        if (suggestion.confidenceScore() >= matchingProperties.getAutoReconcileThreshold()) {
            return BankTransactionStatus.RECONCILED;
        }
        if (suggestion.confidenceScore() >= matchingProperties.getSuggestedThreshold()) {
            return BankTransactionStatus.SUGGESTED_MATCH;
        }
        return matchingProperties.isSuggestOnExactAmount() && amountMatches(suggestion)
                ? BankTransactionStatus.SUGGESTED_MATCH
                : BankTransactionStatus.UNMATCHED;
    }

    /**
     * Client feedback 2026-09-28 (point 6): an expense whose amount equals the bank transaction
     * exactly must show under "Suggested Match" even when nothing else lines up (its name
     * differs from the bank's free-text description, no reference, due date far away) - which
     * alone only scores {@code amount-weight} (50), below {@code suggested-threshold} (70).
     */
    private boolean amountMatches(MatchSuggestion suggestion) {
        return suggestion.criteria().stream()
                .anyMatch(c -> c.type() == MatchCriterionType.AMOUNT && c.met());
    }

    /**
     * Re-runs the matching engine over every still-{@code unmatched} transaction dated in
     * {@code [start, end]} and promotes any that now classify higher - the fix for client
     * feedback 2026-09-28 (point 6): "newly created expense records" never showed under
     * "Suggested Match" because a transaction was only ever classified once, at import, so an
     * expense created <i>after</i> the statement was uploaded was invisible to it forever. This
     * service has no way to be told when the (separate) document service creates an expense, so
     * the dashboard/listing calls this before reading instead (see
     * {@code ReconciliationController}) - cheap enough to do that because candidate invoices and
     * their allocated totals are loaded once for the whole batch, not once per transaction.
     * <p>
     * Only ever moves a transaction <i>up</i> from {@code unmatched}; anything already
     * suggested, reviewed or reconciled is left exactly as it is. Follows the same rules as an
     * import: a score at or above the auto-reconcile threshold reconciles it (attributed to
     * {@link #AUTO_RECONCILE_USER}), otherwise it becomes a suggested match.
     *
     * @return how many transactions changed status
     */
    @Transactional
    public int rematchUnmatched(LocalDate start, LocalDate end) {
        List<BankTransaction> unmatched = bankTransactionRepository
                .findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end);
        if (unmatched.isEmpty()) {
            return 0;
        }

        Map<UUID, List<MatchCandidate>> candidatesByTransaction = candidateResolver.resolveCandidates(unmatched);
        int changed = 0;
        for (BankTransaction transaction : unmatched) {
            MatchSuggestion suggestion = matchingEngine.suggest(transaction, candidatesByTransaction.getOrDefault(transaction.getId(), List.of()));
            BankTransactionStatus status = classify(suggestion);
            if (status == BankTransactionStatus.UNMATCHED) {
                continue;
            }
            transaction.setReconciliationStatus(status);
            transaction.setUpdatedAt(Instant.now());
            bankTransactionRepository.save(transaction);
            if (status == BankTransactionStatus.RECONCILED) {
                autoConfirm(transaction.getId(), suggestion.allocations());
            }
            changed++;
        }
        return changed;
    }

    /**
     * Applies {@code allocations} to {@code transactionId}: one {@link Reconciliation} row
     * covering every allocation in {@code toApply} (see that entity's javadoc for why this
     * became one row per confirm action rather than one per allocation, 2026-09-15), then marks
     * the transaction {@link BankTransactionStatus#RECONCILED}. When {@code allocations} is
     * null/empty, confirms whatever {@link #suggest} currently returns for this transaction
     * instead of requiring the caller to have already fetched it.
     */
    @Transactional
    public void confirmMatch(UUID transactionId, List<InvoiceAllocation> allocations) {
        confirmMatch(transactionId, allocations, currentUser(), currentUserName());
    }

    /**
     * {@link #classify}'s auto-reconcile outcome (score &gt;= {@code auto-reconcile-threshold})
     * calls this instead of {@link #confirmMatch} so the resulting {@link Reconciliation} row is
     * attributed to {@link #AUTO_RECONCILE_USER}, not whichever human happened to be
     * authenticated when the triggering statement was uploaded (or {@code "system"}, which
     * already means something else - an unauthenticated manual call). Same persistence path
     * otherwise.
     */
    @Transactional
    public void autoConfirm(UUID transactionId, List<InvoiceAllocation> allocations) {
        confirmMatch(transactionId, allocations, AUTO_RECONCILE_USER, AUTO_RECONCILE_USER_NAME);
    }

    private void confirmMatch(UUID transactionId, List<InvoiceAllocation> allocations, String createdBy, String createdByName) {
        BankTransaction transaction = findTransaction(transactionId);
        List<InvoiceAllocation> toApply = (allocations == null || allocations.isEmpty())
                ? suggest(transaction).allocations()
                : allocations;
        if (toApply.isEmpty()) {
            throw new IllegalStateException("No allocations to confirm for transaction " + transactionId);
        }

        List<Long> invoiceIds = toApply.stream().map(InvoiceAllocation::invoiceId).toList();
        assertInvoicesExist(invoiceIds);

        reconciliationRepository.save(Reconciliation.builder()
                .bankTransaction(transaction)
                .invoiceIds(invoiceIds)
                .allocatedAmounts(toApply.stream().map(InvoiceAllocation::amount).toList())
                .reconciliationDate(LocalDateTime.now())
                .createdBy(createdBy)
                .createdByName(createdByName)
                .build());

        transaction.setReconciliationStatus(BankTransactionStatus.RECONCILED);
        transaction.setUpdatedAt(Instant.now());
        bankTransactionRepository.save(transaction);
    }

    /**
     * There's no FK to check this for us (see {@link Reconciliation}'s javadoc on why
     * {@code invoice_ids} can't have one) - one batch query for every id in the confirm action
     * rather than one round-trip per invoice, same reasoning as
     * {@link ReconciliationRepository#sumAllocatedAmountsByInvoiceIds}.
     */
    private void assertInvoicesExist(List<Long> invoiceIds) {
        List<Long> found = supplierDocumentsRecordRepository.findAllById(invoiceIds).stream()
                .map(SupplierDocumentsRecord::getId)
                .toList();
        List<Long> missing = invoiceIds.stream().filter(id -> !found.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new EntityNotFoundException("Invoice(s) not found: " + missing);
        }
    }

    /**
     * API 7 ("Approve Match / Reconcile Transaction") - the request carries only
     * {@code invoiceIds}, no amounts (see {@code ReconcileRequest}'s javadoc for why one
     * request shape covers both "accept the suggestion" and "manual selection"). Amounts are
     * computed here by consuming each chosen invoice's <i>current</i> remaining balance, in the
     * order given, capped by the transaction's total amount - then delegates to
     * {@link #confirmMatch} exactly as if the caller had computed those amounts itself.
     * <p>
     * If the chosen invoices' combined remaining balance is less than the transaction amount,
     * the excess is simply left unallocated rather than rejected - there is no confirmed rule
     * for what should happen to a remainder in that case.
     */
    @Transactional
    public BankTransaction reconcile(UUID transactionId, List<Long> invoiceIds) {
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            throw new IllegalArgumentException("invoiceIds must not be empty");
        }
        assertInvoicesExist(invoiceIds);
        BankTransaction transaction = findTransaction(transactionId);
        confirmMatch(transactionId, computeAllocations(transaction, invoiceIds));
        return transaction;
    }

    private List<InvoiceAllocation> computeAllocations(BankTransaction transaction, List<Long> invoiceIds) {
        Map<Long, BigDecimal> alreadyAllocated = reconciliationRepository.sumAllocatedAmountsByInvoiceIds(invoiceIds).stream()
                .collect(Collectors.toMap(InvoiceAllocatedTotal::getInvoiceId, InvoiceAllocatedTotal::getTotal));
        Map<Long, SupplierDocumentsRecord> invoicesById = supplierDocumentsRecordRepository.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(SupplierDocumentsRecord::getId, r -> r));

        BigDecimal remaining = transaction.getAmount().abs();
        List<InvoiceAllocation> allocations = new ArrayList<>();
        for (Long invoiceId : invoiceIds) {
            if (remaining.signum() <= 0) {
                break;
            }
            SupplierDocumentsRecord invoice = invoicesById.get(invoiceId);
            BigDecimal totalDue = invoice.getTotalDue() == null ? BigDecimal.ZERO : BigDecimal.valueOf(invoice.getTotalDue());
            BigDecimal invoiceRemaining = totalDue.subtract(alreadyAllocated.getOrDefault(invoiceId, BigDecimal.ZERO));
            if (invoiceRemaining.signum() <= 0) {
                continue;
            }
            BigDecimal amount = invoiceRemaining.min(remaining);
            allocations.add(new InvoiceAllocation(invoiceId, amount));
            remaining = remaining.subtract(amount);
        }
        return allocations;
    }

    /**
     * API 6 ("Reopen Transaction for Matching") - only valid from {@code needs_review}, per that
     * API's own status-transition diagram (needs_review -&gt; unmatched, nothing else listed).
     */
    @Transactional
    public BankTransaction reopen(UUID transactionId) {
        BankTransaction transaction = findTransaction(transactionId);
        if (transaction.getReconciliationStatus() != BankTransactionStatus.NEEDS_REVIEW) {
            throw new IllegalStateException(
                    "Only a transaction in needs_review can be reopened (was " + transaction.getReconciliationStatus().wireValue() + ")");
        }
        transaction.setReconciliationStatus(BankTransactionStatus.UNMATCHED);
        transaction.setUpdatedAt(Instant.now());
        return bankTransactionRepository.save(transaction);
    }

    /**
     * API 8 ("Mark Transaction for Review"). Per that API's "Important" note, {@code reviewerId}
     * is never taken from the caller - always the authenticated user, same {@link #currentUser}
     * mechanism {@link Reconciliation#getCreatedBy()} already uses.
     */
    @Transactional
    public BankTransaction markForReview(UUID transactionId, String reviewerComment) {
        BankTransaction transaction = findTransaction(transactionId);
        Instant now = Instant.now();
        transaction.setReconciliationStatus(BankTransactionStatus.NEEDS_REVIEW);
        transaction.setReviewerId(currentUser());
        transaction.setReviewerName(currentUserName());
        transaction.setReviewerComment(reviewerComment);
        transaction.setReviewedAt(now);
        transaction.setUpdatedAt(now);
        return bankTransactionRepository.save(transaction);
    }

    private BankTransaction findTransaction(UUID transactionId) {
        return bankTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Bank transaction " + transactionId + " not found"));
    }

    /**
     * "sub" is a standard OIDC/JWT claim (unlike the tenant claim, not a Pilim-specific guess),
     * so this is safe to read directly. Falls back to a constant for the local/dev profile
     * (no JWT, security disabled).
     */
    private String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return "system";
    }

    /**
     * The acting user's full name for "reviewed by" (client feedback 2026-09-28: show
     * "Prince Boghara", not an id). {@code name}, {@code given_name}/{@code family_name},
     * {@code preferred_username} and {@code email} are all standard OIDC claims (the
     * {@code profile}/{@code email} scopes the app's tokens already carry), tried in that order
     * so a token missing the first still yields something readable; the {@code sub} id is the
     * last resort, so this never returns blank for an authenticated caller.
     */
    private String currentUserName() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String fullName = jwt.getClaimAsString("name");
            if (fullName != null && !fullName.isBlank()) {
                return fullName.trim();
            }
            String given = jwt.getClaimAsString("given_name");
            String family = jwt.getClaimAsString("family_name");
            String joined = ((given == null ? "" : given.trim()) + " " + (family == null ? "" : family.trim())).trim();
            if (!joined.isEmpty()) {
                return joined;
            }
            for (String claim : List.of("preferred_username", "email")) {
                String value = jwt.getClaimAsString(claim);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return jwt.getSubject();
        }
        return "system";
    }
}

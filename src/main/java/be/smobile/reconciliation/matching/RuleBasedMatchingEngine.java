package be.smobile.reconciliation.matching;

import be.smobile.reconciliation.entity.BankTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Implements section 7 ("Matching Engine") exactly as confirmed in the milestone-2 discussion:
 * "Phase 2 is solely rule based engine. Total arithmetic calculation." Each of the four
 * weighted criteria is a plain pass/fail check (no partial credit / graduated percentages -
 * the doc's weight table doesn't define a partial-credit curve, and a binary check is what the
 * "Amount matches exactly" / "Customer matches" / "Invoice reference detected" checklist
 * language in the UI mockups actually shows); the confidence score is just the sum of the
 * weights whose criterion passed. See {@link MatchingProperties} for the weights/thresholds.
 * <p>
 * <b>Grouping (1:N, section 5.2; N:N, section 5.4)</b>: restricts to candidates whose
 * {@link MatchCriterionType#PARTNER} matches the transaction (you don't lump one customer's
 * payment against another customer's invoices), caps the set at
 * {@code maxCandidatesForGrouping}, and searches - in this priority order, all via a bounded
 * subset-sum over integer "micros" (scale-4 minor units, matching this entity's
 * {@code precision = 19, scale = 4}, so the search is exact long arithmetic, never floating
 * point):
 * <ol>
 *     <li>an exact-sum subset, each candidate consumed in full (section 5.2's 9-invoice
 *     example);</li>
 *     <li>failing that, the largest such full-consumption subset topped off by exactly ONE more
 *     candidate consumed only <i>partially</i> for whatever remainder is left - client
 *     confirmed 2026-09-13 this should be auto-suggested, per section 5.4's own worked example
 *     (600 EUR -> one invoice in full + a second invoice partially);</li>
 *     <li>failing that too, the single closest-amount candidate (so the UI still has something
 *     to show / let the user adjust), with the AMOUNT criterion correctly reported as not met.</li>
 * </ol>
 * <b>N:1 (section 5.3, multiple payments to one invoice)</b> needs no special handling here: it
 * falls out of {@link MatchCandidate#remainingBalance()} already reflecting prior partial
 * allocations (the caller's job to compute) - the second payment is just matched against
 * whatever balance is left, the same as any other candidate.
 */
@Component
@RequiredArgsConstructor
public class RuleBasedMatchingEngine implements MatchingEngine {

    private final MatchingProperties properties;

    @Override
    public MatchSuggestion suggest(BankTransaction transaction, List<MatchCandidate> candidates) {
        BigDecimal targetAmount = transaction.getAmount().abs();
        String transactionText = normalize(transaction.getDescription()) + " " + normalize(transaction.getReference());
        String transactionPartner = normalize(transaction.getDescription());

        List<MatchCandidate> partnerMatches = candidates.stream()
                .filter(c -> partnerMatches(c, transactionPartner))
                .sorted(Comparator.comparing(c -> dateDistanceDays(transaction.getTransactionDate(), c.dueDate())))
                .limit(properties.getMaxCandidatesForGrouping())
                .toList();

        GroupMatch group = partnerMatches.isEmpty() ? GroupMatch.empty() : findBestGroup(partnerMatches, targetAmount);

        if (!group.exact() && properties.isSuggestOnExactAmount()) {
            Optional<MatchCandidate> amountOnly = exactAmountCandidate(candidates, transaction, targetAmount);
            if (amountOnly.isPresent()) {
                return amountOnlySuggestion(transaction, transactionText, amountOnly.get(), targetAmount);
            }
        }
        if (group.isEmpty()) {
            return MatchSuggestion.none();
        }

        boolean referenceMet = group.candidates().stream().anyMatch(c -> referenceMatches(c, transactionText));
        boolean dateMet = group.candidates().stream()
                .anyMatch(c -> dateDistanceDays(transaction.getTransactionDate(), c.dueDate()) <= properties.getDateToleranceDays());

        List<MatchCriterion> criteria = List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, group.exact()),
                new MatchCriterion(MatchCriterionType.PARTNER, true),
                new MatchCriterion(MatchCriterionType.REFERENCE, referenceMet),
                new MatchCriterion(MatchCriterionType.DATE, dateMet));

        int score = (group.exact() ? properties.getAmountWeight() : 0)
                + properties.getPartnerWeight()
                + (referenceMet ? properties.getReferenceWeight() : 0)
                + (dateMet ? properties.getDateWeight() : 0);

        return new MatchSuggestion(score, group.allocations(), criteria);
    }

    /**
     * Client feedback 2026-09-28 (point 6): an invoice/expense whose open balance equals the
     * transaction amount to the cent is worth showing even when nothing identifies its partner
     * (bank descriptions are free text and often look nothing like the supplier's registered
     * name). Deliberately a <i>single</i> invoice only, never a subset-sum across unrelated
     * partners - lumping arbitrary invoices together on amount alone would suggest nonsense.
     * The closest due date wins when several invoices share the same amount.
     */
    private Optional<MatchCandidate> exactAmountCandidate(List<MatchCandidate> candidates, BankTransaction transaction, BigDecimal targetAmount) {
        long target = toMicros(targetAmount);
        if (target <= 0) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(c -> c.remainingBalance() != null && c.remainingBalance().signum() > 0 && toMicros(c.remainingBalance()) == target)
                .min(Comparator.comparing(c -> dateDistanceDays(transaction.getTransactionDate(), c.dueDate())));
    }

    /** Same scoring as {@link #suggest}'s normal path, minus the partner criterion (which by definition didn't match here). */
    private MatchSuggestion amountOnlySuggestion(BankTransaction transaction, String transactionText, MatchCandidate candidate, BigDecimal targetAmount) {
        boolean referenceMet = referenceMatches(candidate, transactionText);
        boolean dateMet = dateDistanceDays(transaction.getTransactionDate(), candidate.dueDate()) <= properties.getDateToleranceDays();

        List<MatchCriterion> criteria = List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, true),
                new MatchCriterion(MatchCriterionType.PARTNER, false),
                new MatchCriterion(MatchCriterionType.REFERENCE, referenceMet),
                new MatchCriterion(MatchCriterionType.DATE, dateMet));

        int score = properties.getAmountWeight()
                + (referenceMet ? properties.getReferenceWeight() : 0)
                + (dateMet ? properties.getDateWeight() : 0);

        return new MatchSuggestion(score, List.of(new InvoiceAllocation(candidate.invoiceId(), targetAmount)), criteria);
    }

    private boolean partnerMatches(MatchCandidate candidate, String transactionPartner) {
        String candidatePartner = normalize(candidate.partnerName());
        if (candidatePartner.isEmpty() || transactionPartner.isEmpty()) {
            return false;
        }
        return candidatePartner.equals(transactionPartner)
                || transactionPartner.contains(candidatePartner)
                || candidatePartner.contains(transactionPartner);
    }

    private boolean referenceMatches(MatchCandidate candidate, String transactionText) {
        String invoiceNumber = normalize(candidate.invoiceNumber());
        return !invoiceNumber.isEmpty() && transactionText.contains(invoiceNumber);
    }

    private long dateDistanceDays(LocalDate transactionDate, LocalDate dueDate) {
        if (transactionDate == null || dueDate == null) {
            return Long.MAX_VALUE;
        }
        return Math.abs(ChronoUnit.DAYS.between(transactionDate, dueDate));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Optional<MatchCandidate> closestSingleCandidate(List<MatchCandidate> candidates, BigDecimal targetAmount) {
        return candidates.stream()
                .filter(c -> c.remainingBalance() != null && c.remainingBalance().signum() > 0)
                .min(Comparator.comparing(c -> c.remainingBalance().subtract(targetAmount).abs()));
    }

    /** See the class javadoc's 3-tier priority order. {@code exact} is true for tiers 1 and 2 (the allocated total equals the transaction amount either way), false only for tier 3. */
    private record GroupMatch(List<MatchCandidate> candidates, List<InvoiceAllocation> allocations, boolean exact) {
        boolean isEmpty() {
            return allocations.isEmpty();
        }

        static GroupMatch empty() {
            return new GroupMatch(List.of(), List.of(), false);
        }

        static GroupMatch fullyExact(List<MatchCandidate> candidates) {
            List<InvoiceAllocation> allocations = candidates.stream().map(c -> new InvoiceAllocation(c.invoiceId(), c.remainingBalance())).toList();
            return new GroupMatch(candidates, allocations, true);
        }

        static GroupMatch withOnePartial(List<MatchCandidate> fullyConsumed, MatchCandidate partial, BigDecimal partialAmount) {
            List<MatchCandidate> allCandidates = new ArrayList<>(fullyConsumed);
            allCandidates.add(partial);
            List<InvoiceAllocation> allocations = new ArrayList<>(fullyConsumed.stream()
                    .map(c -> new InvoiceAllocation(c.invoiceId(), c.remainingBalance())).toList());
            allocations.add(new InvoiceAllocation(partial.invoiceId(), partialAmount));
            return new GroupMatch(allCandidates, allocations, true);
        }

        static GroupMatch singleFallback(MatchCandidate candidate, BigDecimal amount) {
            return new GroupMatch(List.of(candidate), List.of(new InvoiceAllocation(candidate.invoiceId(), amount)), false);
        }
    }

    /**
     * Implements the 3-tier search from the class javadoc. {@code reachable} maps every sum
     * achievable by fully consuming some subset of {@code candidates} (never exceeding
     * {@code targetMicros}) to the indices making it up - built once, then tier 1 checks it for
     * an exact hit and tier 2 walks it from the largest sum down looking for one more candidate
     * that can be partially consumed to close the gap exactly. Bounded in practice by
     * {@link MatchingProperties#getMaxCandidatesForGrouping()}, per section 10's own "10-50
     * candidates, not 50,000" guidance - subset-sum is exponential in the worst case otherwise.
     */
    private GroupMatch findBestGroup(List<MatchCandidate> candidates, BigDecimal targetAmount) {
        long target = toMicros(targetAmount);
        if (target <= 0) {
            return GroupMatch.empty();
        }

        Map<Long, List<Integer>> reachable = new LinkedHashMap<>();
        reachable.put(0L, List.of());

        for (int i = 0; i < candidates.size(); i++) {
            BigDecimal balance = candidates.get(i).remainingBalance();
            if (balance == null || balance.signum() <= 0) {
                continue;
            }
            long amount = toMicros(balance);
            if (amount > target) {
                continue;
            }

            Map<Long, List<Integer>> additions = new LinkedHashMap<>();
            for (Map.Entry<Long, List<Integer>> entry : reachable.entrySet()) {
                long newSum = entry.getKey() + amount;
                if (newSum > target || reachable.containsKey(newSum) || additions.containsKey(newSum)) {
                    continue;
                }
                List<Integer> newIndices = new ArrayList<>(entry.getValue());
                newIndices.add(i);
                additions.put(newSum, newIndices);
            }
            reachable.putAll(additions);
        }

        // Tier 1: exact full-consumption subset.
        List<Integer> exactIndices = reachable.get(target);
        if (exactIndices != null) {
            return GroupMatch.fullyExact(exactIndices.stream().map(candidates::get).toList());
        }

        // Tier 2: largest full-consumption subset, topped off by one partially-consumed candidate.
        List<Long> sumsDescending = reachable.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        for (Long sum : sumsDescending) {
            List<Integer> subsetIndices = reachable.get(sum);
            long remainder = target - sum;
            if (remainder <= 0) {
                continue;
            }
            for (int i = 0; i < candidates.size(); i++) {
                if (subsetIndices.contains(i)) {
                    continue;
                }
                BigDecimal balance = candidates.get(i).remainingBalance();
                if (balance == null || toMicros(balance) < remainder) {
                    continue;
                }
                List<MatchCandidate> fullyConsumed = subsetIndices.stream().map(candidates::get).toList();
                return GroupMatch.withOnePartial(fullyConsumed, candidates.get(i), fromMicros(remainder));
            }
        }

        // Tier 3: nothing hits the target exactly - closest single candidate, AMOUNT unmet.
        return closestSingleCandidate(candidates, targetAmount)
                .map(c -> GroupMatch.singleFallback(c, c.remainingBalance().min(targetAmount)))
                .orElseGet(GroupMatch::empty);
    }

    private long toMicros(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    private BigDecimal fromMicros(long micros) {
        return BigDecimal.valueOf(micros, 4);
    }
}

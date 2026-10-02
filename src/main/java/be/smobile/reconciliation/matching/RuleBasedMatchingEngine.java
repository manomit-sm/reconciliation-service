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
 * <p>
 * <b>Several candidate suggestions, one winner (client feedback 2026-10-02, points 2/4/6)</b>:
 * the partner-restricted search above is only <i>one</i> option. Bank descriptions are free text
 * and often don't resemble an invoice's registered name, so (unless
 * {@code suggest-on-exact-amount} is off) a second option is also built from <i>all</i>
 * candidates: the fewest invoices - of any partner - whose open balances add up to exactly the
 * transaction amount (a single invoice, or several: two €4,860.50 invoices for different
 * customers paying a €9,721.00 transaction). Each option is scored with the same four criteria
 * and the best one wins, in this order: a suggestion that is actually showable (clears the
 * suggested threshold, or matches the amount exactly) beats one that isn't; then one that uses
 * no <i>future-dated</i> invoice (dated after the payment - far more likely a later invoice than
 * what was paid) beats one that does, so a partial payment of a bigger invoice dated months
 * later can no longer outrank a same-amount, same-date expense; then the higher score; then
 * (ties) amount-matched, partner-matched, fewer invoices, closer in date.
 */
@Component
@RequiredArgsConstructor
public class RuleBasedMatchingEngine implements MatchingEngine {

    private final MatchingProperties properties;

    /** Shortest {@code reference}/order-number worth searching for in a bank text - below this a plain number like "001" matches by pure coincidence. */
    private static final int MIN_EXTRA_REFERENCE_LENGTH = 4;

    @Override
    public MatchSuggestion suggest(BankTransaction transaction, List<MatchCandidate> candidates) {
        BigDecimal targetAmount = transaction.getAmount().abs();
        LocalDate transactionDate = transaction.getTransactionDate();
        String transactionText = normalize(transaction.getDescription()) + " " + normalize(transaction.getReference());
        String transactionPartner = normalize(transaction.getDescription());
        Comparator<MatchCandidate> preferred = preference(transactionDate);

        List<Option> options = new ArrayList<>();

        List<MatchCandidate> partnerMatches = candidates.stream()
                .filter(c -> partnerMatches(c, transactionPartner))
                .sorted(preferred)
                .limit(properties.getMaxCandidatesForGrouping())
                .toList();
        if (!partnerMatches.isEmpty()) {
            GroupMatch group = findBestGroup(partnerMatches, targetAmount);
            if (!group.isEmpty()) {
                options.add(option(group, transaction, transactionText, transactionPartner));
            }
        }

        if (properties.isSuggestOnExactAmount()) {
            List<MatchCandidate> pool = candidates.stream()
                    .filter(c -> c.remainingBalance() != null && c.remainingBalance().signum() > 0)
                    .sorted(preferred)
                    .limit(properties.getMaxCandidatesForGrouping())
                    .toList();
            GroupMatch group = findExactGroup(pool, targetAmount);
            if (!group.isEmpty()) {
                options.add(option(group, transaction, transactionText, transactionPartner));
            }
        }

        return options.stream()
                .min(optionOrder())
                .map(Option::suggestion)
                .orElseGet(MatchSuggestion::none);
    }

    /** One scored candidate suggestion - see the class javadoc for how {@link #optionOrder} ranks them. */
    private record Option(MatchSuggestion suggestion, boolean showable, boolean amountMet, boolean partnerMet,
                          boolean futureDated, int invoiceCount, long dateDistance, long firstInvoiceId) {
    }

    private Option option(GroupMatch group, BankTransaction transaction, String transactionText, String transactionPartner) {
        LocalDate transactionDate = transaction.getTransactionDate();
        boolean partnerMet = group.candidates().stream().allMatch(c -> partnerMatches(c, transactionPartner));
        boolean referenceMet = group.candidates().stream().anyMatch(c -> referenceMatches(c, transactionText));
        boolean dateMet = group.candidates().stream()
                .anyMatch(c -> dateDistanceDays(transactionDate, c) <= properties.getDateToleranceDays());

        List<MatchCriterion> criteria = List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, group.exact()),
                new MatchCriterion(MatchCriterionType.PARTNER, partnerMet),
                new MatchCriterion(MatchCriterionType.REFERENCE, referenceMet),
                new MatchCriterion(MatchCriterionType.DATE, dateMet));

        int score = (group.exact() ? properties.getAmountWeight() : 0)
                + (partnerMet ? properties.getPartnerWeight() : 0)
                + (referenceMet ? properties.getReferenceWeight() : 0)
                + (dateMet ? properties.getDateWeight() : 0);

        boolean showable = score >= properties.getSuggestedThreshold() || (properties.isSuggestOnExactAmount() && group.exact());
        long distance = group.candidates().stream().mapToLong(c -> dateDistanceDays(transactionDate, c)).min().orElse(Long.MAX_VALUE);
        return new Option(
                new MatchSuggestion(score, group.allocations(), criteria),
                showable, group.exact(), partnerMet,
                group.candidates().stream().anyMatch(c -> isFutureDated(c, transactionDate)),
                group.allocations().size(), distance,
                group.allocations().getFirst().invoiceId());
    }

    /** Best option first - see the class javadoc. */
    private Comparator<Option> optionOrder() {
        return Comparator.<Option, Boolean>comparing(o -> !o.showable())
                .thenComparing(Option::futureDated)
                .thenComparing(o -> -o.suggestion().confidenceScore())
                .thenComparing(o -> !o.amountMet())
                .thenComparing(o -> !o.partnerMet())
                .thenComparingInt(Option::invoiceCount)
                .thenComparingLong(Option::dateDistance)
                .thenComparingLong(Option::firstInvoiceId);
    }

    /**
     * Best candidate first: not future-dated before future-dated, then the closest in date
     * (client feedback 2026-10-02, point 6: of two same-amount invoices, the one dated before the
     * payment is the likelier one, not the one dated after it), then lowest id for a stable order.
     */
    private Comparator<MatchCandidate> preference(LocalDate transactionDate) {
        return Comparator.<MatchCandidate, Boolean>comparing(c -> isFutureDated(c, transactionDate))
                .thenComparingLong(c -> dateDistanceDays(transactionDate, c))
                .thenComparing(MatchCandidate::invoiceId);
    }

    private boolean isFutureDated(MatchCandidate candidate, LocalDate transactionDate) {
        return candidate.invoiceDate() != null && transactionDate != null && candidate.invoiceDate().isAfter(transactionDate);
    }

    /**
     * The fewest invoices - of any partner - whose full open balances add up to exactly
     * {@code targetAmount}; empty if there's no such set. Client feedback 2026-10-02, points 2 and
     * 4. {@code candidates} must already be in {@link #preference} order so that, among equally
     * small sets, the preferred invoices are the ones found first.
     */
    private GroupMatch findExactGroup(List<MatchCandidate> candidates, BigDecimal targetAmount) {
        long target = toMicros(targetAmount);
        if (target <= 0) {
            return GroupMatch.empty();
        }
        List<Integer> indices = reachableSums(candidates, target).get(target);
        if (indices == null || indices.isEmpty()) {
            return GroupMatch.empty();
        }
        return GroupMatch.fullyExact(indices.stream().map(candidates::get).toList());
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

    /**
     * Client feedback 2026-10-02 (point 5, "does not detect reference match"): an invoice created
     * with the transaction reference "JAN-2026-0027" never scored REFERENCE because only its
     * <i>invoice number</i> ("INV00027", auto-generated) was searched for in the bank text. The
     * document's own {@code reference} and order number are searched too - any of them appearing
     * in the transaction's description or reference counts.
     */
    private boolean referenceMatches(MatchCandidate candidate, String transactionText) {
        String invoiceNumber = normalize(candidate.invoiceNumber());
        if (!invoiceNumber.isEmpty() && transactionText.contains(invoiceNumber)) {
            return true;
        }
        return candidate.references().stream()
                .map(this::normalize)
                .anyMatch(ref -> ref.length() >= MIN_EXTRA_REFERENCE_LENGTH && transactionText.contains(ref));
    }

    /**
     * Days between the transaction and the closer of the invoice's <i>invoice date</i> and
     * <i>due date</i> (client feedback 2026-10-02, point 5: comparing the due date alone scored
     * "Date Match 0" for an expense with exactly the transaction's date, just because it was due
     * months later). {@link Long#MAX_VALUE} if neither is known.
     */
    private long dateDistanceDays(LocalDate transactionDate, MatchCandidate candidate) {
        if (transactionDate == null) {
            return Long.MAX_VALUE;
        }
        long best = Long.MAX_VALUE;
        for (LocalDate date : new LocalDate[]{candidate.invoiceDate(), candidate.dueDate()}) {
            if (date != null) {
                best = Math.min(best, Math.abs(ChronoUnit.DAYS.between(transactionDate, date)));
            }
        }
        return best;
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

        Map<Long, List<Integer>> reachable = reachableSums(candidates, target);

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


    /**
     * Every sum {@code <= target} reachable by fully consuming some subset of {@code candidates},
     * mapped to the indices making it up - the <i>smallest</i> such subset (earliest indices on a
     * tie, i.e. the most preferred candidates), so an exact hit never uses more invoices than it
     * needs to.
     */
    private Map<Long, List<Integer>> reachableSums(List<MatchCandidate> candidates, long target) {
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
                if (newSum > target) {
                    continue;
                }
                List<Integer> current = additions.containsKey(newSum) ? additions.get(newSum) : reachable.get(newSum);
                if (current != null && current.size() <= entry.getValue().size() + 1) {
                    continue;
                }
                List<Integer> newIndices = new ArrayList<>(entry.getValue());
                newIndices.add(i);
                additions.put(newSum, newIndices);
            }
            reachable.putAll(additions);
        }
        return reachable;
    }

    private long toMicros(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    private BigDecimal fromMicros(long micros) {
        return BigDecimal.valueOf(micros, 4);
    }
}

package be.smobile.reconciliation.matching;

import java.util.List;

/**
 * The result of {@link MatchingEngine#suggest}: a confidence score (0-100, the literal sum of
 * whichever section-7 criteria weights were satisfied - see {@link RuleBasedMatchingEngine}) and
 * the invoice allocation(s) that earned it. {@link #allocations()} may hold more than one entry
 * (the 1:N "one payment, several invoices" case, section 5.2) or be empty (no candidate was even
 * close - the "Unmatched" / "No reliable match found" screen).
 * <p>
 * Classifying this into a {@link be.smobile.reconciliation.model.enums.BankTransactionStatus} -
 * confidence bands, section 7 - is the caller's job, not this record's: whether 70 counts as
 * "Suggested" depends on {@code app.matching.suggested-threshold}, which lives in
 * {@link MatchingProperties}, not here.
 */
public record MatchSuggestion(int confidenceScore, List<InvoiceAllocation> allocations, List<MatchCriterion> criteria) {

    public static MatchSuggestion none() {
        return new MatchSuggestion(0, List.of(), List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, false),
                new MatchCriterion(MatchCriterionType.PARTNER, false),
                new MatchCriterion(MatchCriterionType.REFERENCE, false),
                new MatchCriterion(MatchCriterionType.DATE, false)));
    }

    public boolean isEmpty() {
        return allocations.isEmpty();
    }
}

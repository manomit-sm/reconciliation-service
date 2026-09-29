package be.smobile.reconciliation.matching;

import be.smobile.reconciliation.model.dto.ScoringDetail;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns a {@link MatchSuggestion} into the {@code matching_criteria}/{@code scoring_details}
 * text API 4.3 (suggested_match) of the "Frontend API Requirements" doc shows - e.g. "Customer
 * name matches" / "Invoice references INV-2026-001, 002, 003 detected". Kept separate from
 * {@link RuleBasedMatchingEngine} the same way {@code InvoiceStatusPresenter} is kept separate
 * from {@code InvoiceStatusCalculator}: the engine decides what's met, this only formats it.
 */
@Component
public class MatchingCriteriaPresenter {

    public List<String> describeCriteria(MatchSuggestion suggestion, List<MatchCandidate> matchedCandidates, boolean customerInvoice) {
        List<String> lines = new ArrayList<>();
        for (MatchCriterion criterion : suggestion.criteria()) {
            if (!criterion.met()) {
                continue;
            }
            switch (criterion.type()) {
                case AMOUNT -> lines.add("Amount matches exactly");
                case PARTNER -> lines.add((customerInvoice ? "Customer" : "Supplier") + " name matches");
                case REFERENCE -> lines.add(referenceLine(matchedCandidates));
                case DATE -> lines.add("Date matches within tolerance");
            }
        }
        return lines;
    }

    private String referenceLine(List<MatchCandidate> matchedCandidates) {
        String invoiceNumbers = matchedCandidates.stream()
                .map(MatchCandidate::invoiceNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        String noun = matchedCandidates.size() > 1 ? "references" : "reference";
        return "Invoice " + noun + " " + invoiceNumbers + " detected";
    }

    public List<ScoringDetail> scoringDetails(MatchSuggestion suggestion, MatchingProperties properties) {
        return List.of(new ScoringDetail(
                points(suggestion, MatchCriterionType.AMOUNT, properties.getAmountWeight()),
                points(suggestion, MatchCriterionType.PARTNER, properties.getPartnerWeight()),
                points(suggestion, MatchCriterionType.REFERENCE, properties.getReferenceWeight()),
                points(suggestion, MatchCriterionType.DATE, properties.getDateWeight())));
    }

    /** {@code "+<weight>"} when the criterion was met, {@code "0"} otherwise - see {@link ScoringDetail}'s javadoc. */
    private String points(MatchSuggestion suggestion, MatchCriterionType type, int weight) {
        boolean met = suggestion.criteria().stream()
                .filter(c -> c.type() == type)
                .findFirst()
                .map(MatchCriterion::met)
                .orElse(false);
        return met ? "+" + weight : "0";
    }
}

package be.smobile.reconciliation.matching;

import be.smobile.reconciliation.model.dto.ScoringDetail;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatchingCriteriaPresenterTest {

    private final MatchingCriteriaPresenter presenter = new MatchingCriteriaPresenter();
    private final MatchingProperties properties = new MatchingProperties(); // 50/30/10/10 defaults

    @Test
    void describeCriteria_onlyDescribesMetCriteria_inOrder() {
        MatchSuggestion suggestion = new MatchSuggestion(90, List.of(), List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, true),
                new MatchCriterion(MatchCriterionType.PARTNER, true),
                new MatchCriterion(MatchCriterionType.REFERENCE, false),
                new MatchCriterion(MatchCriterionType.DATE, true)));
        MatchCandidate candidate = new MatchCandidate(1L, "INV-2026-001", "Customer ABC", LocalDate.now(), BigDecimal.TEN);

        List<String> lines = presenter.describeCriteria(suggestion, List.of(candidate), true);

        assertEquals(List.of("Amount matches exactly", "Customer name matches", "Date matches within tolerance"), lines);
    }

    @Test
    void describeCriteria_referenceLine_listsEveryMatchedInvoiceNumber_pluralWhenMoreThanOne() {
        MatchSuggestion suggestion = new MatchSuggestion(10, List.of(), List.of(new MatchCriterion(MatchCriterionType.REFERENCE, true)));
        MatchCandidate first = new MatchCandidate(1L, "INV-2026-001", "Customer ABC", LocalDate.now(), BigDecimal.TEN);
        MatchCandidate second = new MatchCandidate(2L, "INV-2026-002", "Customer ABC", LocalDate.now(), BigDecimal.TEN);

        List<String> lines = presenter.describeCriteria(suggestion, List.of(first, second), true);

        assertEquals(List.of("Invoice references INV-2026-001, INV-2026-002 detected"), lines);
    }

    @Test
    void describeCriteria_partnerLine_distinguishesCustomerFromSupplier() {
        MatchSuggestion suggestion = new MatchSuggestion(30, List.of(), List.of(new MatchCriterion(MatchCriterionType.PARTNER, true)));

        assertEquals(List.of("Customer name matches"), presenter.describeCriteria(suggestion, List.of(), true));
        assertEquals(List.of("Supplier name matches"), presenter.describeCriteria(suggestion, List.of(), false));
    }

    @Test
    void scoringDetails_earnedCriteriaShowTheirConfiguredWeight_unmetShowZero() {
        MatchSuggestion suggestion = new MatchSuggestion(80, List.of(), List.of(
                new MatchCriterion(MatchCriterionType.AMOUNT, true),
                new MatchCriterion(MatchCriterionType.PARTNER, true),
                new MatchCriterion(MatchCriterionType.REFERENCE, false),
                new MatchCriterion(MatchCriterionType.DATE, false)));

        List<ScoringDetail> details = presenter.scoringDetails(suggestion, properties);

        assertEquals(List.of(new ScoringDetail("+50", "+30", "0", "0")), details);
    }
}

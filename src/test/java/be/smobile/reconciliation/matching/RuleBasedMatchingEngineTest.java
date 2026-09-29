package be.smobile.reconciliation.matching;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cases are the design doc's own worked examples (section 5.1/5.2) plus the tolerance/no-match
 * edges around them, so the engine's behavior is pinned to what the client actually wrote down,
 * not just to whatever the implementation happens to do.
 */
class RuleBasedMatchingEngineTest {

    private final MatchingProperties properties = new MatchingProperties();
    private final RuleBasedMatchingEngine engine = new RuleBasedMatchingEngine(properties);

    // Section 5.1: BT (Customer ABC, 250 EUR) <-> INV-001 (Customer ABC, 250 EUR, ref in transaction) = 1:1, 100% confidence.
    @Test
    void scenario1_oneToOne_allCriteriaMet_isFullConfidence() {
        BankTransaction transaction = transaction("Customer ABC", "Payment INV-001", "250.00", LocalDate.of(2026, 9, 5));
        MatchCandidate invoice = candidate(1L, "INV-001", "Customer ABC", LocalDate.of(2026, 9, 5), "250.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(invoice));

        assertEquals(100, suggestion.confidenceScore());
        assertEquals(1, suggestion.allocations().size());
        assertEquals(1L, suggestion.allocations().getFirst().invoiceId());
        assertEquals(0, suggestion.allocations().getFirst().amount().compareTo(new BigDecimal("250.00")));
        assertTrue(suggestion.criteria().stream().allMatch(MatchCriterion::met));
    }

    // Section 5.2: BT (Customer ABC, +2000 EUR) <-> 9 open invoices summing exactly to 2000 EUR.
    @Test
    void scenario2_oneToMany_exactGroupSum_matchesAllNineInvoices() {
        BankTransaction transaction = transaction("Customer ABC", "", "2000.00", LocalDate.of(2026, 9, 10));
        List<MatchCandidate> invoices = List.of(
                candidate(1L, "INV001", "Customer ABC", LocalDate.of(2026, 9, 12), "100.00"),
                candidate(2L, "INV002", "Customer ABC", LocalDate.of(2026, 9, 12), "200.00"),
                candidate(3L, "INV003", "Customer ABC", LocalDate.of(2026, 9, 12), "150.00"),
                candidate(4L, "INV004", "Customer ABC", LocalDate.of(2026, 9, 12), "300.00"),
                candidate(5L, "INV005", "Customer ABC", LocalDate.of(2026, 9, 12), "250.00"),
                candidate(6L, "INV006", "Customer ABC", LocalDate.of(2026, 9, 12), "180.00"),
                candidate(7L, "INV007", "Customer ABC", LocalDate.of(2026, 9, 12), "120.00"),
                candidate(8L, "INV008", "Customer ABC", LocalDate.of(2026, 9, 12), "400.00"),
                candidate(9L, "INV009", "Customer ABC", LocalDate.of(2026, 9, 12), "300.00"));

        MatchSuggestion suggestion = engine.suggest(transaction, invoices);

        assertEquals(9, suggestion.allocations().size());
        BigDecimal allocatedTotal = suggestion.allocations().stream().map(InvoiceAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, allocatedTotal.compareTo(new BigDecimal("2000.00")));
        assertTrue(suggestion.criteria().stream().filter(c -> c.type() == MatchCriterionType.AMOUNT).findFirst().orElseThrow().met());
        // Weights: amount(50) + partner(30) + date(10) = 90; no invoice number appears in the (blank) transaction text.
        assertEquals(90, suggestion.confidenceScore());
    }

    /**
     * Section 5.4 (N:N)'s own worked example, both payments, run end-to-end exactly the way
     * {@code ReconciliationService} would (candidates' remaining balances shrink between calls
     * as prior allocations land). Client confirmed 2026-09-13 this partial split should be
     * auto-suggested, not left to manual allocation - see the class javadoc's tier 2.
     * <p>
     * Payment 1 - BT001 = 600 EUR against 3 open invoices (500/200/300, none of which sum to
     * 600 on their own): the engine finds INV001 in full (500) + INV002 partially (100 of its
     * 200), exactly matching the doc's own suggested split, with AMOUNT reported as met (the
     * allocated total is still exactly 600, just not from full invoices alone).
     * <p>
     * Payment 2 - BT002 = 400 EUR against what's left open (INV002's remaining 100, INV003's
     * 300): now sums exactly via full consumption of both (100 + 300 = 400) - doc's own
     * "BT002 -> INV002 -> 100, BT002 -> INV003 -> 300".
     */
    @Test
    void scenario4_manyToMany_partialInvoiceSplitIsAutoSuggested_matchesBothDocPayments() {
        BankTransaction payment1 = transaction("Customer ABC", "", "600.00", LocalDate.of(2026, 9, 1));
        List<MatchCandidate> openInvoices = List.of(
                candidate(1L, "INV001", "Customer ABC", LocalDate.of(2026, 9, 1), "500.00"),
                candidate(2L, "INV002", "Customer ABC", LocalDate.of(2026, 9, 1), "200.00"),
                candidate(3L, "INV003", "Customer ABC", LocalDate.of(2026, 9, 1), "300.00"));

        MatchSuggestion suggestion1 = engine.suggest(payment1, openInvoices);

        assertEquals(2, suggestion1.allocations().size());
        assertTrue(suggestion1.criteria().stream().filter(c -> c.type() == MatchCriterionType.AMOUNT).findFirst().orElseThrow().met());
        InvoiceAllocation inv001Allocation = suggestion1.allocations().stream().filter(a -> a.invoiceId().equals(1L)).findFirst().orElseThrow();
        InvoiceAllocation inv002Allocation = suggestion1.allocations().stream().filter(a -> a.invoiceId().equals(2L)).findFirst().orElseThrow();
        assertEquals(0, inv001Allocation.amount().compareTo(new BigDecimal("500.00")));
        assertEquals(0, inv002Allocation.amount().compareTo(new BigDecimal("100.0000")));

        // Payment 2, against the balances left after payment 1 (INV001 fully closed, INV002 down to 100, INV003 untouched).
        BankTransaction payment2 = transaction("Customer ABC", "", "400.00", LocalDate.of(2026, 9, 2));
        List<MatchCandidate> remainingInvoices = List.of(
                candidate(2L, "INV002", "Customer ABC", LocalDate.of(2026, 9, 1), "100.00"),
                candidate(3L, "INV003", "Customer ABC", LocalDate.of(2026, 9, 1), "300.00"));

        MatchSuggestion suggestion2 = engine.suggest(payment2, remainingInvoices);

        assertEquals(2, suggestion2.allocations().size());
        assertTrue(suggestion2.criteria().stream().filter(c -> c.type() == MatchCriterionType.AMOUNT).findFirst().orElseThrow().met());
        BigDecimal total2 = suggestion2.allocations().stream().map(InvoiceAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total2.compareTo(new BigDecimal("400.00")));
    }

    @Test
    void noCandidateSharesThePartnerNameAndNoAmountMatches_returnsNone() {
        BankTransaction transaction = transaction("Office Depot", "", "120.00", LocalDate.of(2026, 9, 5));
        MatchCandidate invoice = candidate(1L, "INV-777", "Customer ABC", LocalDate.of(2026, 9, 5), "999.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(invoice));

        assertTrue(suggestion.isEmpty());
        assertEquals(0, suggestion.confidenceScore());
    }

    /** Client feedback 2026-09-28 (point 6): an equal-amount expense must be suggested even when its name doesn't resemble the bank's description. */
    @Test
    void noCandidateSharesThePartnerNameButOneMatchesTheAmount_suggestsItOnAmountAlone() {
        BankTransaction transaction = transaction("Office Depot", "", "120.00", LocalDate.of(2026, 9, 5));
        MatchCandidate invoice = candidate(1L, "INV-777", "Customer ABC", LocalDate.of(2026, 3, 1), "120.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(invoice));

        assertEquals(1, suggestion.allocations().size());
        assertEquals(1L, suggestion.allocations().get(0).invoiceId());
        assertEquals(0, suggestion.allocations().get(0).amount().compareTo(new BigDecimal("120.00")));
        assertEquals(50, suggestion.confidenceScore());
        assertTrue(suggestion.criteria().stream().filter(c -> c.type() == MatchCriterionType.AMOUNT).findFirst().orElseThrow().met());
        assertFalse(suggestion.criteria().stream().filter(c -> c.type() == MatchCriterionType.PARTNER).findFirst().orElseThrow().met());
    }

    @Test
    void severalInvoicesMatchTheAmount_theOneDueClosestToTheTransactionWins() {
        BankTransaction transaction = transaction("Office Depot", "", "120.00", LocalDate.of(2026, 9, 5));
        MatchCandidate far = candidate(1L, "INV-1", "Other Ltd", LocalDate.of(2026, 3, 1), "120.00");
        MatchCandidate near = candidate(2L, "INV-2", "Third Ltd", LocalDate.of(2026, 9, 3), "120.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(far, near));

        assertEquals(2L, suggestion.allocations().get(0).invoiceId());
    }

    @Test
    void exactAmountFallbackCanBeSwitchedOff() {
        MatchingProperties strict = new MatchingProperties();
        strict.setSuggestOnExactAmount(false);
        BankTransaction transaction = transaction("Office Depot", "", "120.00", LocalDate.of(2026, 9, 5));
        MatchCandidate invoice = candidate(1L, "INV-777", "Customer ABC", LocalDate.of(2026, 9, 5), "120.00");

        assertTrue(new RuleBasedMatchingEngine(strict).suggest(transaction, List.of(invoice)).isEmpty());
    }

    @Test
    void partnerMatchesButAmountDoesNot_fallsBackToClosestSingleCandidate_amountCriterionUnmet() {
        BankTransaction transaction = transaction("Customer XYZ", "", "2000.00", LocalDate.of(2026, 9, 4));
        MatchCandidate invoice = candidate(1L, "INV-500", "Customer XYZ", LocalDate.of(2026, 9, 4), "1800.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(invoice));

        assertEquals(1, suggestion.allocations().size());
        assertEquals(0, suggestion.allocations().getFirst().amount().compareTo(new BigDecimal("1800.0000")));
        assertFalse(suggestion.criteria().stream().filter(c -> c.type() == MatchCriterionType.AMOUNT).findFirst().orElseThrow().met());
        // partner(30) + date(10), amount and reference unmet -> below the default 70 suggested threshold.
        assertEquals(40, suggestion.confidenceScore());
        assertTrue(suggestion.confidenceScore() < properties.getSuggestedThreshold());
    }

    @Test
    void dateFarOutsideTolerance_dateCriterionUnmet_butStillSuggestedOnAmountAndPartner() {
        BankTransaction transaction = transaction("Wholesale Co", "", "600.00", LocalDate.of(2026, 9, 1));
        MatchCandidate invoice = candidate(1L, "INV-900", "Wholesale Co", LocalDate.of(2026, 10, 15), "600.00");

        MatchSuggestion suggestion = engine.suggest(transaction, List.of(invoice));

        assertFalse(suggestion.criteria().stream().filter(c -> c.type() == MatchCriterionType.DATE).findFirst().orElseThrow().met());
        // amount(50) + partner(30) = 80, still >= suggested threshold (70).
        assertEquals(80, suggestion.confidenceScore());
    }

    private BankTransaction transaction(String description, String reference, String amount, LocalDate date) {
        return BankTransaction.builder()
                .description(description)
                .reference(reference)
                .amount(new BigDecimal(amount))
                .direction(TransactionDirection.CREDIT)
                .transactionDate(date)
                .build();
    }

    private MatchCandidate candidate(Long id, String invoiceNumber, String partnerName, LocalDate dueDate, String remainingBalance) {
        return new MatchCandidate(id, invoiceNumber, partnerName, dueDate, new BigDecimal(remainingBalance));
    }
}

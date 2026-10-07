package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins down the FIFO waterfall exactly against the client's 2026-09-19 screenshot's own worked
 * numbers - see {@link MultiPaymentAllocationService}'s javadoc.
 */
@ExtendWith(MockitoExtension.class)
class MultiPaymentAllocationServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private ReconciliationCandidateResolver candidateResolver;
    @Mock
    private ReconciliationService reconciliationService;

    private final UUID accountId = UUID.randomUUID();

    private MultiPaymentAllocationService service() {
        return new MultiPaymentAllocationService(bankTransactionRepository, candidateResolver, reconciliationService, new MatchingProperties());
    }

    /** "Wholesale Co sent 2 payments covering 3 open invoices" - 600 -> INV-030 (500) + INV-031 (100); 400 -> INV-031 (100) + INV-032 (300). */
    @Test
    void findGroup_screenshotScenario_producesTheExactWaterfallShown() {
        BankTransaction paymentA = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        BankTransaction paymentB = payment(LocalDate.of(2026, 8, 30), new BigDecimal("400.00"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(paymentA, paymentB));

        MatchCandidate inv030 = candidate(30L, LocalDate.of(2026, 9, 10), new BigDecimal("500.00"));
        MatchCandidate inv031 = candidate(31L, LocalDate.of(2026, 9, 27), new BigDecimal("200.00"));
        MatchCandidate inv032 = candidate(32L, LocalDate.of(2026, 10, 3), new BigDecimal("300.00"));
        when(candidateResolver.resolveCandidates(paymentA)).thenReturn(List.of(inv030, inv031, inv032));

        Optional<MultiPaymentAllocationService.PaymentGroup> group = service().findGroup(paymentA);

        assertTrue(group.isPresent());
        List<MultiPaymentAllocationService.PaymentAllocation> allocations = group.get().allocations();
        assertEquals(4, allocations.size());
        assertAllocation(allocations.get(0), paymentA, 30L, "500.00");
        assertAllocation(allocations.get(1), paymentA, 31L, "100.00");
        assertAllocation(allocations.get(2), paymentB, 31L, "100.00");
        assertAllocation(allocations.get(3), paymentB, 32L, "300.00");
    }

    @Test
    void findGroup_onlyOneSiblingPayment_returnsEmpty() {
        BankTransaction payment = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(payment));

        assertTrue(service().findGroup(payment).isEmpty());
    }

    @Test
    void findGroup_onlyOneOpenInvoiceForThePartner_returnsEmpty() {
        BankTransaction paymentA = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        BankTransaction paymentB = payment(LocalDate.of(2026, 8, 30), new BigDecimal("400.00"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(paymentA, paymentB));
        when(candidateResolver.resolveCandidates(paymentA)).thenReturn(List.of(candidate(30L, LocalDate.of(2026, 9, 10), new BigDecimal("1000.00"))));

        assertTrue(service().findGroup(paymentA).isEmpty());
    }

    @Test
    void findGroup_paymentsExceedCombinedInvoiceBalance_returnsEmpty() {
        BankTransaction paymentA = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        BankTransaction paymentB = payment(LocalDate.of(2026, 8, 30), new BigDecimal("400.00"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(paymentA, paymentB));
        when(candidateResolver.resolveCandidates(paymentA)).thenReturn(List.of(
                candidate(30L, LocalDate.of(2026, 9, 10), new BigDecimal("500.00")),
                candidate(31L, LocalDate.of(2026, 9, 27), new BigDecimal("100.00"))));

        assertTrue(service().findGroup(paymentA).isEmpty());
    }

    @Test
    void approveGroup_reconcilesEveryPaymentAgainstItsOwnShare() {
        BankTransaction paymentA = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        BankTransaction paymentB = payment(LocalDate.of(2026, 8, 30), new BigDecimal("400.00"));
        when(bankTransactionRepository.findById(paymentA.getId())).thenReturn(Optional.of(paymentA));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(paymentA, paymentB));
        when(candidateResolver.resolveCandidates(paymentA)).thenReturn(List.of(
                candidate(30L, LocalDate.of(2026, 9, 10), new BigDecimal("500.00")),
                candidate(31L, LocalDate.of(2026, 9, 27), new BigDecimal("200.00")),
                candidate(32L, LocalDate.of(2026, 10, 3), new BigDecimal("300.00"))));

        service().approveGroup(paymentA.getId());

        verify(reconciliationService).confirmMatch(paymentA.getId(), List.of(
                new InvoiceAllocation(30L, new BigDecimal("500.00")),
                new InvoiceAllocation(31L, new BigDecimal("100.00"))));
        verify(reconciliationService).confirmMatch(paymentB.getId(), List.of(
                new InvoiceAllocation(31L, new BigDecimal("100.00")),
                new InvoiceAllocation(32L, new BigDecimal("300.00"))));
    }

    @Test
    void approveGroup_noGroupCurrentlyAvailable_throwsConflict() {
        BankTransaction payment = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        when(bankTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(payment));

        assertThrows(IllegalStateException.class, () -> service().approveGroup(payment.getId()));
    }

    @Test
    void approveGroup_unknownTransaction_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(bankTransactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service().approveGroup(id));
    }

    /** The client's own mockup shows a single "Mark for review" button on the multi-payment panel, not one per payment. */
    @Test
    void markGroupForReview_flagsEveryPaymentInTheGroupWithTheSameComment() {
        BankTransaction paymentA = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        BankTransaction paymentB = payment(LocalDate.of(2026, 8, 30), new BigDecimal("400.00"));
        when(bankTransactionRepository.findById(paymentA.getId())).thenReturn(Optional.of(paymentA));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(paymentA, paymentB));
        when(candidateResolver.resolveCandidates(paymentA)).thenReturn(List.of(
                candidate(30L, LocalDate.of(2026, 9, 10), new BigDecimal("500.00")),
                candidate(31L, LocalDate.of(2026, 9, 27), new BigDecimal("200.00")),
                candidate(32L, LocalDate.of(2026, 10, 3), new BigDecimal("300.00"))));

        service().markGroupForReview(paymentA.getId(), "Needs a closer look");

        verify(reconciliationService).markForReview(paymentA.getId(), "Needs a closer look");
        verify(reconciliationService).markForReview(paymentB.getId(), "Needs a closer look");
    }

    @Test
    void markGroupForReview_noGroupCurrentlyAvailable_throwsConflict() {
        BankTransaction payment = payment(LocalDate.of(2026, 8, 30), new BigDecimal("600.00"));
        when(bankTransactionRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(payment));

        assertThrows(IllegalStateException.class, () -> service().markGroupForReview(payment.getId(), "comment"));
    }

    @Test
    void markGroupForReview_unknownTransaction_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(bankTransactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service().markGroupForReview(id, "comment"));
    }

    // ---- Several payments, one invoice (client feedback 2026-10-07, point 1) - the client's own figures ----

    /**
     * Invoice INV00037 (28,150.71, dated 18 Jan, due 6 Oct) was created as the sum of three bank
     * transactions of the same day with three different descriptions; they stayed "Unmatched".
     */
    private final BankTransaction p80 = payment("January Transaction #80", LocalDate.of(2026, 1, 18), new BigDecimal("862.36"));
    private final BankTransaction p49 = payment("January Transaction #49", LocalDate.of(2026, 1, 18), new BigDecimal("15078.03"));
    private final BankTransaction p18 = payment("January Transaction #18", LocalDate.of(2026, 1, 18), new BigDecimal("12210.32"));

    private MatchCandidate inv37() {
        return new MatchCandidate(37L, "INV00037", "January Transaction #27", LocalDate.of(2026, 10, 6),
                new BigDecimal("28150.71"), LocalDate.of(2026, 1, 18), List.of());
    }

    @Test
    void findGroup_threePaymentsWithDifferentDescriptionsThatPayOneInvoice_areGrouped() {
        BankTransaction unrelatedSameDay = payment("Something else", LocalDate.of(2026, 1, 18), new BigDecimal("100.00"));
        BankTransaction unrelatedLater = payment("Much later", LocalDate.of(2026, 3, 1), new BigDecimal("500.00"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses()))
                .thenReturn(List.of(p80, p49, p18, unrelatedSameDay, unrelatedLater));
        when(candidateResolver.resolveCandidates(p49)).thenReturn(List.of(inv37()));

        Optional<MultiPaymentAllocationService.PaymentGroup> group = service().findGroup(p49);

        assertTrue(group.isPresent());
        assertEquals(3, group.get().payments().size());
        assertEquals(List.of(p80.getId(), p49.getId(), p18.getId()).stream().sorted().toList(),
                group.get().payments().stream().map(BankTransaction::getId).sorted().toList());
        assertEquals(3, group.get().allocations().size());
        group.get().allocations().forEach(a -> {
            assertEquals(37L, a.invoiceId());
            assertEquals(0, a.payment().getAmount().compareTo(a.amount()));
        });
        BigDecimal total = group.get().allocations().stream().map(MultiPaymentAllocationService.PaymentAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, total.compareTo(new BigDecimal("28150.71")));
    }

    @Test
    void findGroup_paymentsThatAreOffByACent_areNotGrouped() {
        BankTransaction off = payment("January Transaction #18", LocalDate.of(2026, 1, 18), new BigDecimal("12210.33"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses())).thenReturn(List.of(p80, p49, off));
        when(candidateResolver.resolveCandidates(p49)).thenReturn(List.of(inv37()));

        assertTrue(service().findGroup(p49).isEmpty());
    }

    @Test
    void findGroup_paymentsFarFromTheInvoiceDate_areNotGrouped() {
        BankTransaction early = payment("a", LocalDate.of(2026, 1, 1), new BigDecimal("862.36"));
        BankTransaction mid = payment("b", LocalDate.of(2026, 1, 1), new BigDecimal("15078.03"));
        BankTransaction late = payment("c", LocalDate.of(2026, 1, 1), new BigDecimal("12210.32"));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses())).thenReturn(List.of(early, mid, late));
        when(candidateResolver.resolveCandidates(mid)).thenReturn(List.of(inv37()));

        assertTrue(service().findGroup(mid).isEmpty());
    }

    @Test
    void findGroup_aPaymentInTheOppositeDirectionIsNeverPartOfTheGroup() {
        BankTransaction moneyOut = BankTransaction.builder().id(UUID.randomUUID()).accountId(accountId).description("out").amount(new BigDecimal("12210.32"))
                .direction(TransactionDirection.DEBIT).transactionDate(LocalDate.of(2026, 1, 18)).createdAt(Instant.EPOCH).reconciliationStatus(BankTransactionStatus.UNMATCHED).build();
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses())).thenReturn(List.of(p80, p49, moneyOut));
        when(candidateResolver.resolveCandidates(p49)).thenReturn(List.of(inv37()));

        assertTrue(service().findGroup(p49).isEmpty());
    }

    @Test
    void approveGroup_forSeveralPaymentsOneInvoice_reconcilesEachPaymentAgainstItsOwnAmount() {
        when(bankTransactionRepository.findById(p49.getId())).thenReturn(Optional.of(p49));
        when(bankTransactionRepository.findByAccountIdAndReconciliationStatusIn(accountId, groupableStatuses())).thenReturn(List.of(p80, p49, p18));
        when(candidateResolver.resolveCandidates(p49)).thenReturn(List.of(inv37()));

        service().approveGroup(p49.getId());

        verify(reconciliationService).confirmMatch(p80.getId(), List.of(new InvoiceAllocation(37L, new BigDecimal("862.36"))));
        verify(reconciliationService).confirmMatch(p49.getId(), List.of(new InvoiceAllocation(37L, new BigDecimal("15078.03"))));
        verify(reconciliationService).confirmMatch(p18.getId(), List.of(new InvoiceAllocation(37L, new BigDecimal("12210.32"))));
    }

    @Test
    void promoteGroupedPayments_movesTheThreePaymentsToSuggested_andLeavesTheRestUnmatched() {
        BankTransaction unrelated = payment("Something else", LocalDate.of(2026, 1, 18), new BigDecimal("100.00"));
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        List<BankTransaction> unmatched = List.of(p80, p49, p18, unrelated);
        unmatched.forEach(tx -> tx.setReconciliationStatus(BankTransactionStatus.UNMATCHED));
        when(bankTransactionRepository.findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end)).thenReturn(unmatched);
        when(candidateResolver.resolveCandidates(unmatched)).thenReturn(java.util.Map.of(
                p80.getId(), List.of(inv37()), p49.getId(), List.of(inv37()), p18.getId(), List.of(inv37()), unrelated.getId(), List.of(inv37())));

        int promoted = service().promoteGroupedPayments(start, end);

        assertEquals(3, promoted);
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, p80.getReconciliationStatus());
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, p49.getReconciliationStatus());
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, p18.getReconciliationStatus());
        assertEquals(BankTransactionStatus.UNMATCHED, unrelated.getReconciliationStatus());
        verify(bankTransactionRepository).save(p80);
        verify(bankTransactionRepository, org.mockito.Mockito.never()).save(unrelated);
        verify(reconciliationService, org.mockito.Mockito.never()).confirmMatch(any(), any());
    }

    @Test
    void promoteGroupedPayments_nothingToGroup_changesNothing() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        when(bankTransactionRepository.findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end)).thenReturn(List.of(p80));

        assertEquals(0, service().promoteGroupedPayments(start, end));
    }

    private BankTransaction payment(String description, LocalDate date, BigDecimal amount) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .description(description)
                .amount(amount)
                .direction(TransactionDirection.CREDIT)
                .transactionDate(date)
                .createdAt(Instant.EPOCH.plusSeconds(paymentSequence++))
                .reconciliationStatus(BankTransactionStatus.UNMATCHED)
                .build();
    }

    private void assertAllocation(MultiPaymentAllocationService.PaymentAllocation allocation, BankTransaction expectedPayment,
                                   long expectedInvoiceId, String expectedAmount) {
        assertEquals(expectedPayment, allocation.payment());
        assertEquals(expectedInvoiceId, allocation.invoiceId());
        assertEquals(0, new BigDecimal(expectedAmount).compareTo(allocation.amount()));
    }

    private List<BankTransactionStatus> groupableStatuses() {
        return List.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.SUGGESTED_MATCH);
    }

    /** Same-day payments (matching the screenshots, both "30 Aug") sort by createdAt - see the service's own javadoc - so callers pass payments in the intended waterfall order and each gets a later createdAt than the one before it. */
    private int paymentSequence = 0;

    private BankTransaction payment(LocalDate date, BigDecimal amount) {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .accountId(accountId)
                .description("Wholesale Co")
                .amount(amount)
                .direction(TransactionDirection.CREDIT)
                .transactionDate(date)
                .createdAt(Instant.EPOCH.plusSeconds(paymentSequence++))
                .reconciliationStatus(BankTransactionStatus.SUGGESTED_MATCH)
                .build();
    }

    private MatchCandidate candidate(long invoiceId, LocalDate dueDate, BigDecimal remainingBalance) {
        return new MatchCandidate(invoiceId, "INV-" + invoiceId, "Wholesale Co", dueDate, remainingBalance);
    }
}

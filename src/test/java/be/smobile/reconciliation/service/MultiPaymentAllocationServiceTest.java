package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCandidate;
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
        return new MultiPaymentAllocationService(bankTransactionRepository, candidateResolver, reconciliationService);
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

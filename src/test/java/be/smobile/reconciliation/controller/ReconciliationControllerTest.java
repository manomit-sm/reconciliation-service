package be.smobile.reconciliation.controller;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.imports.BankStatementUploadService;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.model.dto.AlternativeInvoice;
import be.smobile.reconciliation.model.dto.AlternativeMatchesResponse;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.service.BankTransactionDtoMapper;
import be.smobile.reconciliation.service.MultiPaymentAllocationService;
import be.smobile.reconciliation.service.ReconciliationCandidateResolver;
import be.smobile.reconciliation.service.ReconciliationDashboardService;
import be.smobile.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationControllerTest {

    @Mock
    private ReconciliationDashboardService dashboardService;
    @Mock
    private BankStatementUploadService uploadService;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private ReconciliationCandidateResolver candidateResolver;
    @Mock
    private ReconciliationService reconciliationService;
    @Mock
    private BankTransactionDtoMapper dtoMapper;
    @Mock
    private MultiPaymentAllocationService multiPaymentAllocationService;

    private ReconciliationController controller() {
        return new ReconciliationController(dashboardService, uploadService, bankTransactionRepository, candidateResolver,
                reconciliationService, dtoMapper, multiPaymentAllocationService);
    }

    // ---- Find Alternatives Search Enhancement (client feedback 2026-09-29) ----

    @Test
    void alternativeMatches_searchMatchesBySupplierNameAsWellAsInvoiceNumber() {
        UUID transactionId = UUID.randomUUID();
        BankTransaction transaction = BankTransaction.builder().id(transactionId).build();
        when(bankTransactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        MatchCandidate byNumber = new MatchCandidate(1L, "INV00002", "Amazon Marketplace Europe", LocalDate.of(2026, 9, 25), new BigDecimal("726.00"));
        MatchCandidate bySupplier = new MatchCandidate(2L, "EX2026092813011110", "LA VIGNETTE", LocalDate.of(2026, 9, 28), new BigDecimal("9288.94"));
        MatchCandidate neither = new MatchCandidate(3L, "INV00099", "Someone Else", LocalDate.of(2026, 9, 1), new BigDecimal("10.00"));
        when(candidateResolver.resolveCandidates(transaction)).thenReturn(List.of(byNumber, bySupplier, neither));
        when(dtoMapper.alternativeInvoices(anyList())).thenAnswer(inv -> {
            List<MatchCandidate> candidates = inv.getArgument(0);
            return candidates.stream().map(c -> new AlternativeInvoice(c.invoiceId(), c.invoiceNumber(), null, null, null, null, c.partnerName(), null)).toList();
        });

        AlternativeMatchesResponse byInvoiceNumber = controller().alternativeMatches(transactionId, "INV00002", 1, 20);
        assertEquals(1, byInvoiceNumber.data().size());
        assertEquals(1L, byInvoiceNumber.data().get(0).id());

        AlternativeMatchesResponse bySupplierName = controller().alternativeMatches(transactionId, "vignette", 1, 20);
        assertEquals(1, bySupplierName.data().size());
        assertEquals(2L, bySupplierName.data().get(0).id());
    }

    // ---- Date Range Filter Addition (client feedback 2026-09-29) ----

    @Test
    void getTransactions_startAndEndDate_reMatchOverThatRangeNotTheWholeMonth() {
        when(bankTransactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf());
        when(dtoMapper.toSummaries(anyList())).thenReturn(List.of());
        LocalDate start = LocalDate.of(2026, 9, 5);
        LocalDate end = LocalDate.of(2026, 10, 12);

        controller().getTransactions(2026, 1, null, null, 1, 20, start, end);

        verify(reconciliationService).rematchUnmatched(start, end);
    }

    @Test
    void getTransactions_yearMonthOnly_reMatchesTheWholeCalendarMonth() {
        when(bankTransactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf());
        when(dtoMapper.toSummaries(anyList())).thenReturn(List.of());

        controller().getTransactions(2026, 9, null, null, 1, 20, null, null);

        verify(reconciliationService).rematchUnmatched(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void getTransactions_startDateWithoutEndDate_fallsBackToYearMonth() {
        when(bankTransactionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageOf());
        when(dtoMapper.toSummaries(anyList())).thenReturn(List.of());

        controller().getTransactions(2026, 9, null, null, 1, 20, LocalDate.of(2026, 9, 5), null);

        verify(reconciliationService).rematchUnmatched(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void getTransactions_passesStartAndEndDateThroughToTheSpecification() {
        ArgumentCaptor<Specification<BankTransaction>> captor = ArgumentCaptor.forClass(Specification.class);
        when(bankTransactionRepository.findAll(captor.capture(), any(Pageable.class))).thenReturn(pageOf());
        when(dtoMapper.toSummaries(anyList())).thenReturn(List.of());
        LocalDate start = LocalDate.of(2026, 9, 5);
        LocalDate end = LocalDate.of(2026, 10, 12);

        controller().getTransactions(null, null, null, null, 1, 20, start, end);

        // Built at all (a non-null Specification instance) - the exact predicate is BankTransactionSpecificationsTest's job.
        assertEquals(false, captor.getValue() == null);
    }

    // ---- Summary API: startDate/endDate (client feedback 2026-10-02, point 1) ----

    @Test
    void summary_withStartAndEndDate_summarisesAndReMatchesOverThatRange() {
        LocalDate start = LocalDate.of(2026, 9, 25);
        LocalDate end = LocalDate.of(2026, 10, 2);
        ReconciliationSummaryResponse expected = new ReconciliationSummaryResponse(start, end, new ReconciliationSummaryResponse.Summary(1, 2, 3, 4, 5));
        when(dashboardService.summary(start, end)).thenReturn(expected);

        assertEquals(expected, controller().summary(start, end, null, null));

        verify(reconciliationService).rematchUnmatched(start, end);
    }

    @Test
    void summary_dateRangeWinsOverYearMonthWhenBothAreSent() {
        LocalDate start = LocalDate.of(2026, 9, 25);
        LocalDate end = LocalDate.of(2026, 10, 2);
        ReconciliationSummaryResponse expected = new ReconciliationSummaryResponse(start, end, new ReconciliationSummaryResponse.Summary(0, 0, 0, 0, 0));
        when(dashboardService.summary(start, end)).thenReturn(expected);

        assertEquals(expected, controller().summary(start, end, 2026, 1));
    }

    @Test
    void summary_withYearAndMonthStillWorks() {
        ReconciliationSummaryResponse expected = new ReconciliationSummaryResponse(2026, 9, new ReconciliationSummaryResponse.Summary(0, 0, 0, 0, 0));
        when(dashboardService.summary(2026, 9)).thenReturn(expected);

        assertEquals(expected, controller().summary(null, null, 2026, 9));

        verify(reconciliationService).rematchUnmatched(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void summary_withNoPeriodAtAll_orAHalfPeriod_orABadMonth_isABadRequest() {
        assertThrows(IllegalArgumentException.class, () -> controller().summary(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> controller().summary(LocalDate.of(2026, 9, 25), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> controller().summary(null, null, 2026, null));
        assertThrows(IllegalArgumentException.class, () -> controller().summary(null, null, 2026, 13));
    }

    private Page<BankTransaction> pageOf() {
        return new PageImpl<>(List.of());
    }
}

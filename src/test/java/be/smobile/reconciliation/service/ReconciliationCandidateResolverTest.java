package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationCandidateResolverTest {

    @Mock
    private SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;

    @Mock
    private ReconciliationRepository reconciliationRepository;

    private ReconciliationCandidateResolver resolver() {
        return new ReconciliationCandidateResolver(supplierDocumentsRecordRepository, reconciliationRepository);
    }

    @Test
    void creditTransaction_usesClientNameAsPartner_andSubtractsExistingAllocations() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder()
                .id(1L)
                .clientName("Customer ABC")
                .businessName("Should not be used for a CREDIT transaction")
                .invoiceNumber("INV-2026-001")
                .dueDate(LocalDate.of(2026, 9, 24))
                .totalDue(500.0)
                .build();

        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(record));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList()))
                .thenReturn(List.of(allocatedTotal(1L, new BigDecimal("100.00"))));

        BankTransaction transaction = BankTransaction.builder()
                .direction(TransactionDirection.CREDIT)
                .transactionDate(LocalDate.of(2026, 9, 20))
                .build();

        List<MatchCandidate> candidates = resolver().resolveCandidates(transaction);

        assertEquals(1, candidates.size());
        MatchCandidate candidate = candidates.getFirst();
        assertEquals("Customer ABC", candidate.partnerName());
        assertEquals(0, candidate.remainingBalance().compareTo(new BigDecimal("400.00"))); // 500 - 100 already allocated
    }

    @Test
    void debitTransaction_usesBusinessNameAsPartner() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder()
                .id(2L)
                .businessName("Microsoft")
                .clientName("Should not be used for a DEBIT transaction")
                .dueDate(LocalDate.of(2026, 9, 5))
                .totalDue(250.0)
                .build();

        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(record));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        BankTransaction transaction = BankTransaction.builder()
                .direction(TransactionDirection.DEBIT)
                .transactionDate(LocalDate.of(2026, 9, 5))
                .build();

        List<MatchCandidate> candidates = resolver().resolveCandidates(transaction);

        assertEquals("Microsoft", candidates.getFirst().partnerName());
        assertEquals(0, candidates.getFirst().remainingBalance().compareTo(new BigDecimal("250.0")));
    }

    @Test
    void fullyAllocatedInvoice_isExcluded() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder()
                .id(3L)
                .clientName("Customer ABC")
                .dueDate(LocalDate.of(2026, 9, 5))
                .totalDue(300.0)
                .build();

        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(record));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList()))
                .thenReturn(List.of(allocatedTotal(3L, new BigDecimal("300.00"))));

        BankTransaction transaction = BankTransaction.builder()
                .direction(TransactionDirection.CREDIT)
                .transactionDate(LocalDate.of(2026, 9, 5))
                .build();

        assertTrue(resolver().resolveCandidates(transaction).isEmpty());
    }

    /** Client feedback 2026-09-28 (point 6): a newly created expense usually has no due date yet and must still be a candidate. */
    @Test
    void invoiceWithNoDueDate_isStillACandidate() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder()
                .id(4L).businessName("Office Depot").totalDue(120.0).build();
        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(record));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        BankTransaction transaction = BankTransaction.builder()
                .direction(TransactionDirection.DEBIT)
                .transactionDate(LocalDate.of(2026, 9, 5))
                .build();

        List<MatchCandidate> candidates = resolver().resolveCandidates(transaction);

        assertEquals(1, candidates.size());
        assertEquals(4L, candidates.getFirst().invoiceId());
    }

    @Test
    void expenseWithBlankInvoiceNumber_usesItsReferenceAsTheCandidateNumber() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder()
                .id(5L).businessName("LA VIGNETTE").invoiceNumber("").reference("EX2026092813011110")
                .dueDate(LocalDate.of(2026, 9, 28)).totalDue(9288.94).build();
        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(record));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        BankTransaction transaction = BankTransaction.builder()
                .direction(TransactionDirection.DEBIT).transactionDate(LocalDate.of(2026, 9, 28)).build();

        assertEquals("EX2026092813011110", resolver().resolveCandidates(transaction).getFirst().invoiceNumber());
    }

    /** The batch form loads the tables once but still gives each transaction only invoices inside its own 6-month window. */
    @Test
    void batch_readsOnceAndNarrowsEachTransactionToItsOwnWindow() {
        SupplierDocumentsRecord old = SupplierDocumentsRecord.builder().id(1L).clientName("A").dueDate(LocalDate.of(2026, 1, 1)).totalDue(10.0).build();
        SupplierDocumentsRecord recent = SupplierDocumentsRecord.builder().id(2L).clientName("A").dueDate(LocalDate.of(2026, 8, 1)).totalDue(20.0).build();
        when(supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(any())).thenReturn(List.of(old, recent));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        java.util.UUID earlyId = java.util.UUID.randomUUID();
        java.util.UUID lateId = java.util.UUID.randomUUID();
        BankTransaction early = BankTransaction.builder().id(earlyId).direction(TransactionDirection.CREDIT).transactionDate(LocalDate.of(2026, 3, 1)).build();
        BankTransaction late = BankTransaction.builder().id(lateId).direction(TransactionDirection.CREDIT).transactionDate(LocalDate.of(2026, 9, 30)).build();

        java.util.Map<java.util.UUID, List<MatchCandidate>> result = resolver().resolveCandidates(List.of(early, late));

        assertEquals(2, result.get(earlyId).size());          // window from 2025-09-01: both
        assertEquals(List.of(2L), result.get(lateId).stream().map(MatchCandidate::invoiceId).toList()); // window from 2026-03-30: only the recent one
        org.mockito.Mockito.verify(supplierDocumentsRecordRepository, org.mockito.Mockito.times(1)).findByDueDateGreaterThanEqualOrDueDateIsNull(any());
    }

    private InvoiceAllocatedTotal allocatedTotal(Long invoiceId, BigDecimal total) {
        return new InvoiceAllocatedTotal() {
            @Override
            public Long getInvoiceId() {
                return invoiceId;
            }

            @Override
            public BigDecimal getTotal() {
                return total;
            }
        };
    }
}

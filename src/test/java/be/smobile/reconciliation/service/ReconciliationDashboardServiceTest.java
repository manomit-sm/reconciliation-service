package be.smobile.reconciliation.service;

import be.smobile.reconciliation.config.SummaryProperties;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceStatusCalculator;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Covers API 1 ("Get Reconciliation Summary"). {@link InvoiceStatusCalculator}/
 * {@link InvoiceTypeResolver} are used as real instances, not mocks - both are simple,
 * dependency-free, already-tested pure classes, so exercising them for real here gives more
 * confidence in the actual overdue-count outcome than stubbing their answers would.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationDashboardServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    @Mock
    private ReconciliationRepository reconciliationRepository;

    private ReconciliationDashboardService service() {
        return new ReconciliationDashboardService(bankTransactionRepository, supplierDocumentsRecordRepository,
                reconciliationRepository, new InvoiceStatusCalculator(), new InvoiceTypeResolver(), new SummaryProperties());
    }

    /** Client feedback 2026-10-02, point 1: the dashboard now asks for an explicit startDate/endDate range. */
    @Test
    void summary_byDateRange_countsOverThatRangeAndEchoesIt() {
        LocalDate start = LocalDate.of(2026, 9, 25);
        LocalDate end = LocalDate.of(2026, 10, 2);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.UNMATCHED)).thenReturn(3L);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.SUGGESTED_MATCH)).thenReturn(2L);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.NEEDS_REVIEW)).thenReturn(1L);
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of());

        ReconciliationSummaryResponse response = service().summary(start, end);

        assertEquals(new ReconciliationSummaryResponse(start, end, new ReconciliationSummaryResponse.Summary(3, 2, 1, 0, 0)), response);
        assertEquals(null, response.year());
        assertEquals(null, response.month());
    }

    @Test
    void summary_reportsTransactionCountsPerStatus_forTheSelectedMonth() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.UNMATCHED)).thenReturn(12L);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.SUGGESTED_MATCH)).thenReturn(8L);
        when(bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.NEEDS_REVIEW)).thenReturn(4L);
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of());

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(new ReconciliationSummaryResponse(2026, 9,
                new ReconciliationSummaryResponse.Summary(12, 8, 4, 0, 0)), response);
    }

    @Test
    void overdueCounts_splitByCustomerVsSupplier_viaSupplierIdHeuristic() {
        SupplierDocumentsRecord overdueCustomerInvoice = SupplierDocumentsRecord.builder()
                .id(1L).totalDue(100.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).build(); // no supplierId -> customer
        SupplierDocumentsRecord overdueSupplierInvoice = SupplierDocumentsRecord.builder()
                .id(2L).totalDue(200.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).supplierId(55L).build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(overdueCustomerInvoice, overdueSupplierInvoice));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(1, response.summary().overdueCustomers());
        assertEquals(1, response.summary().overdueSuppliers());
    }

    @Test
    void overdueCounts_fullyAllocatedInvoice_isNotOverdue() {
        SupplierDocumentsRecord paidOff = SupplierDocumentsRecord.builder()
                .id(1L).totalDue(100.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(paidOff));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList()))
                .thenReturn(List.of(allocatedTotal(1L, new BigDecimal("100.00"))));

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(0, response.summary().overdueCustomers());
        assertEquals(0, response.summary().overdueSuppliers());
    }

    @Test
    void overdueCounts_draftInvoice_isExcluded() {
        SupplierDocumentsRecord draft = SupplierDocumentsRecord.builder()
                .id(1L).totalDue(100.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).status((short) 0).build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(draft));

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(0, response.summary().overdueCustomers());
        assertEquals(0, response.summary().overdueSuppliers());
    }

    private static final LocalDate SEPT = LocalDate.of(2026, 9, 10);

    private SupplierDocumentsRecord overdue(long id, String crdr, LocalDate invoiceDate, Integer status) {
        return SupplierDocumentsRecord.builder().id(id).crdr(crdr).totalDue(100.0).invoiceDate(invoiceDate)
                .dueDate(invoiceDate.plusDays(1)).status(status == null ? null : status.shortValue()).build();
    }

    private ReconciliationSummaryResponse.Summary summaryOf(List<SupplierDocumentsRecord> records, LocalDate start, LocalDate end) {
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(records);
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());
        return service().summary(start, end).summary();
    }

    /**
     * Client feedback 2026-10-07 (points 2/3): the cards showed the same 20 / 16 whatever range
     * was selected, because every document the account ever had was counted.
     */
    @Test
    void overdueCounts_onlyCountDocumentsDatedInsideTheSelectedRange() {
        List<SupplierDocumentsRecord> records = List.of(
                overdue(1, "credit", LocalDate.of(2026, 5, 28), 4),
                overdue(2, "credit", LocalDate.of(2026, 5, 29), 4),
                overdue(3, "credit", LocalDate.of(2025, 11, 3), 4),   // outside the range
                overdue(4, "debit", LocalDate.of(2026, 1, 29), 0),
                overdue(5, "debit", LocalDate.of(2025, 12, 31), 0));  // outside the range

        ReconciliationSummaryResponse.Summary inRange = summaryOf(records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 31));
        assertEquals(2, inRange.overdueCustomers());
        assertEquals(1, inRange.overdueSuppliers());
    }

    @Test
    void overdueCounts_changeWhenTheRangeChanges() {
        List<SupplierDocumentsRecord> records = List.of(
                overdue(1, "credit", LocalDate.of(2026, 5, 28), 4),
                overdue(2, "debit", LocalDate.of(2026, 1, 29), 0));

        assertEquals(1, summaryOf(records, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)).overdueCustomers());
        assertEquals(0, summaryOf(records, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)).overdueSuppliers());
        assertEquals(0, summaryOf(records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).overdueCustomers());
        assertEquals(1, summaryOf(records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).overdueSuppliers());
    }

    /** The client's expense list: paid rows (status 6 / paid_status 1) are not overdue; a new, unpaid expense (status 0) is. */
    @Test
    void overdueCounts_aDocumentMarkedPaidIsNotOverdue_butAnUnpaidStatusZeroExpenseIs() {
        SupplierDocumentsRecord paidByStatus = overdue(1, "debit", SEPT, 6);
        SupplierDocumentsRecord paidByFlag = SupplierDocumentsRecord.builder().id(2L).crdr("debit").totalDue(100.0)
                .invoiceDate(SEPT).dueDate(SEPT.plusDays(1)).paidStatus(1).build();
        SupplierDocumentsRecord unpaidNewExpense = overdue(3, "debit", SEPT, 0);

        ReconciliationSummaryResponse.Summary summary = summaryOf(List.of(paidByStatus, paidByFlag, unpaidNewExpense),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(1, summary.overdueSuppliers());
    }

    @Test
    void overdueCounts_aSalesInvoiceNotYetSentOrInDraftIsNotOverdue_butASentOneIs() {
        SupplierDocumentsRecord toBeSent = overdue(1, "credit", SEPT, 1);
        SupplierDocumentsRecord draftStatus = overdue(2, "credit", SEPT, 0);
        SupplierDocumentsRecord draftFlag = SupplierDocumentsRecord.builder().id(3L).crdr("credit").totalDue(100.0)
                .invoiceDate(SEPT).dueDate(SEPT.plusDays(1)).status((short) 4).draftInvoice(true).build();
        SupplierDocumentsRecord sent = overdue(4, "credit", SEPT, 4);

        assertEquals(1, summaryOf(List.of(toBeSent, draftStatus, draftFlag, sent), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).overdueCustomers());
    }

    @Test
    void overdueCounts_creditAndDebitNotesBelongToNeitherCard() {
        ReconciliationSummaryResponse.Summary summary = summaryOf(
                List.of(overdue(1, "debit_note", SEPT, 1), overdue(2, "credit_note", SEPT, 4)), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertEquals(0, summary.overdueCustomers());
        assertEquals(0, summary.overdueSuppliers());
    }

    @Test
    void overdueCounts_theExcludedStatusesAreConfigurable() {
        SummaryProperties properties = new SummaryProperties();
        properties.setOverdueExcludedCustomerStatuses(List.of(3));
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(overdue(1, "credit", SEPT, 3), overdue(2, "credit", SEPT, 1)));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        ReconciliationDashboardService custom = new ReconciliationDashboardService(bankTransactionRepository, supplierDocumentsRecordRepository,
                reconciliationRepository, new InvoiceStatusCalculator(), new InvoiceTypeResolver(), properties);

        assertEquals(1, custom.summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).summary().overdueCustomers());
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

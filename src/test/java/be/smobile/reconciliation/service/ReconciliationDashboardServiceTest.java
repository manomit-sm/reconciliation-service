package be.smobile.reconciliation.service;

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
                reconciliationRepository, new InvoiceStatusCalculator(), new InvoiceTypeResolver());
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
                .id(1L).totalDue(100.0).dueDate(LocalDate.of(2026, 1, 1)).build(); // no supplierId -> customer
        SupplierDocumentsRecord overdueSupplierInvoice = SupplierDocumentsRecord.builder()
                .id(2L).totalDue(200.0).dueDate(LocalDate.of(2026, 1, 1)).supplierId(55L).build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(overdueCustomerInvoice, overdueSupplierInvoice));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(anyList())).thenReturn(List.of());

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(1, response.summary().overdueCustomers());
        assertEquals(1, response.summary().overdueSuppliers());
    }

    @Test
    void overdueCounts_fullyAllocatedInvoice_isNotOverdue() {
        SupplierDocumentsRecord paidOff = SupplierDocumentsRecord.builder()
                .id(1L).totalDue(100.0).dueDate(LocalDate.of(2026, 1, 1)).build();
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
                .id(1L).totalDue(100.0).dueDate(LocalDate.of(2026, 1, 1)).status((short) 0).build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(draft));

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(0, response.summary().overdueCustomers());
        assertEquals(0, response.summary().overdueSuppliers());
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

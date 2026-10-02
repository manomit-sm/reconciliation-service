package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceStatusCalculator;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API 1 ("Get Reconciliation Summary") of the 2026-09-16 "Frontend API Requirements" doc - the
 * Reconciliation Dashboard's summary cards. Deliberately its own service, not folded into
 * {@link ReconciliationService}: that class's own javadoc already scopes itself to the "engine
 * core" (suggest/classify/confirm), explicitly excluding dashboard summaries as Milestone 3
 * "REST APIs (dashboard, actions)" scope - which is exactly what this now is.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationDashboardService {

    private final BankTransactionRepository bankTransactionRepository;
    private final SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final InvoiceStatusCalculator invoiceStatusCalculator;
    private final InvoiceTypeResolver invoiceTypeResolver;

    /**
     * "0/8 = Draft" per {@code SupplierDocumentsRecord.status}'s own (unconfirmed) javadoc - the
     * only draft/cancelled signal available to exclude non-issued documents from the overdue
     * counts. "Cancelled" has no documented code at all, so it can't be excluded here.
     */
    private static final short DRAFT_STATUS_A = 0;
    private static final short DRAFT_STATUS_B = 8;

    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse summary(int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        return new ReconciliationSummaryResponse(year, month, summaryFor(ym.atDay(1), ym.atEndOfMonth()));
    }

    /**
     * Same counters over an explicit {@code [startDate, endDate]} range (both inclusive) instead
     * of one calendar month - client feedback 2026-10-02, point 1: the dashboard now sends
     * {@code startDate}/{@code endDate} and the year/month-only form couldn't express that.
     */
    @Transactional(readOnly = true)
    public ReconciliationSummaryResponse summary(LocalDate startDate, LocalDate endDate) {
        return new ReconciliationSummaryResponse(startDate, endDate, summaryFor(startDate, endDate));
    }

    private ReconciliationSummaryResponse.Summary summaryFor(LocalDate start, LocalDate end) {
        long unmatched = bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.UNMATCHED);
        long suggested = bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.SUGGESTED_MATCH);
        long needsReview = bankTransactionRepository.countByTransactionDateBetweenAndReconciliationStatus(start, end, BankTransactionStatus.NEEDS_REVIEW);

        OverdueCounts overdue = overdueCounts();

        return new ReconciliationSummaryResponse.Summary(unmatched, suggested, needsReview, overdue.customers(), overdue.suppliers());
    }

    private record OverdueCounts(long customers, long suppliers) {
    }

    private OverdueCounts overdueCounts() {
        LocalDate today = LocalDate.now();
        List<SupplierDocumentsRecord> candidates = supplierDocumentsRecordRepository.findByDueDateLessThan(today).stream()
                .filter(r -> r.getStatus() == null || (r.getStatus() != DRAFT_STATUS_A && r.getStatus() != DRAFT_STATUS_B))
                .toList();
        if (candidates.isEmpty()) {
            return new OverdueCounts(0, 0);
        }

        Map<Long, BigDecimal> allocated = allocatedAmountsByInvoiceId(candidates);

        long customers = 0;
        long suppliers = 0;
        for (SupplierDocumentsRecord record : candidates) {
            if (!isOverdue(record, allocated.getOrDefault(record.getId(), BigDecimal.ZERO), today)) {
                continue;
            }
            if (invoiceTypeResolver.resolve(record) == InvoiceType.CUSTOMER) {
                customers++;
            } else {
                suppliers++;
            }
        }
        return new OverdueCounts(customers, suppliers);
    }

    private boolean isOverdue(SupplierDocumentsRecord record, BigDecimal paidAmount, LocalDate today) {
        if (record.getDueDate() == null) {
            return false;
        }
        BigDecimal totalDue = record.getTotalDue() == null ? BigDecimal.ZERO : BigDecimal.valueOf(record.getTotalDue());
        BigDecimal balance = totalDue.subtract(paidAmount);
        InvoiceType type = invoiceTypeResolver.resolve(record);
        return invoiceStatusCalculator.calculate(type, paidAmount, balance, record.getDueDate(), today) == InvoiceStatus.OVERDUE;
    }

    private Map<Long, BigDecimal> allocatedAmountsByInvoiceId(List<SupplierDocumentsRecord> records) {
        List<Long> ids = records.stream().map(SupplierDocumentsRecord::getId).toList();
        return reconciliationRepository.sumAllocatedAmountsByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(InvoiceAllocatedTotal::getInvoiceId, InvoiceAllocatedTotal::getTotal));
    }
}

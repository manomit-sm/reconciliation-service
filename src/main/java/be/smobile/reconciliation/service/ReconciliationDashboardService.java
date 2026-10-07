package be.smobile.reconciliation.service;

import be.smobile.reconciliation.config.SummaryProperties;
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
    private final SummaryProperties summaryProperties;

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

        OverdueCounts overdue = overdueCounts(start, end);

        return new ReconciliationSummaryResponse.Summary(unmatched, suggested, needsReview, overdue.customers(), overdue.suppliers());
    }

    private record OverdueCounts(long customers, long suppliers) {
    }

    /**
     * Client feedback 2026-10-07 (points 2 and 3): the cards showed a "static" 20 customers / 16
     * suppliers whatever range was selected, and neither matched the Invoices / Expenses lists for
     * that range. They were counted over <i>every</i> document the account has ever had, and a
     * few rules disagreed with those lists:
     * <ul>
     *   <li><b>date range</b> - now only documents whose invoice date falls in
     *   {@code [start, end]}, the same date the Invoices / Expenses pages filter by;</li>
     *   <li><b>already paid</b> - a document marked paid in the document service (status 6 /
     *   paid_status 1) was still counted as overdue, because only this module's own
     *   reconciliation allocations were consulted;</li>
     *   <li><b>drafts</b> - a new expense has status 0 ("To Be Paid" in the list), which used
     *   to be skipped as a draft; which statuses are skipped is now per side, see
     *   {@link SummaryProperties}, and an invoice's own {@code draft_invoice} flag is honoured;</li>
     *   <li><b>customer vs supplier</b> - by {@code crdr} ("credit" = sales invoice, "debit" =
     *   expense), the same flag the lists' Credit/Debit column shows; credit/debit notes are in
     *   neither list and no longer counted as either.</li>
     * </ul>
     */
    private OverdueCounts overdueCounts(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        List<SupplierDocumentsRecord> candidates = supplierDocumentsRecordRepository.findByDueDateLessThan(today).stream()
                .filter(r -> within(documentDate(r), start, end))
                .filter(r -> !Boolean.TRUE.equals(r.getDraftInvoice()))
                .filter(r -> !markedPaid(r))
                .toList();
        if (candidates.isEmpty()) {
            return new OverdueCounts(0, 0);
        }

        Map<Long, BigDecimal> allocated = allocatedAmountsByInvoiceId(candidates);

        long customers = 0;
        long suppliers = 0;
        for (SupplierDocumentsRecord record : candidates) {
            InvoiceType side = side(record);
            if (side == null || excludedStatus(record, side)) {
                continue;
            }
            if (!isOverdue(record, side, allocated.getOrDefault(record.getId(), BigDecimal.ZERO), today)) {
                continue;
            }
            if (side == InvoiceType.CUSTOMER) {
                customers++;
            } else {
                suppliers++;
            }
        }
        return new OverdueCounts(customers, suppliers);
    }

    /** The date the Invoices / Expenses pages filter by ("Invoice Date"); falls back to the generic date, then the due date, so an undated row isn't silently dropped. */
    private LocalDate documentDate(SupplierDocumentsRecord record) {
        if (record.getInvoiceDate() != null) {
            return record.getInvoiceDate();
        }
        return record.getDate() != null ? record.getDate() : record.getDueDate();
    }

    private boolean within(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    /** "Mark as Paid" in the document service sets status 6 and paid_status 1 (seen in the client's payloads). */
    private boolean markedPaid(SupplierDocumentsRecord record) {
        return (record.getStatus() != null && record.getStatus() == 6)
                || (record.getPaidStatus() != null && record.getPaidStatus() == 1);
    }

    /** {@code crdr} first; blank falls back to {@link InvoiceTypeResolver}'s heuristic; anything else (debit_note, credit_note...) belongs to neither card. */
    private InvoiceType side(SupplierDocumentsRecord record) {
        String crdr = record.getCrdr() == null ? "" : record.getCrdr().trim();
        if ("debit".equalsIgnoreCase(crdr)) {
            return InvoiceType.SUPPLIER;
        }
        if ("credit".equalsIgnoreCase(crdr)) {
            return InvoiceType.CUSTOMER;
        }
        return crdr.isEmpty() ? invoiceTypeResolver.resolve(record) : null;
    }

    private boolean excludedStatus(SupplierDocumentsRecord record, InvoiceType side) {
        if (record.getStatus() == null) {
            return false;
        }
        List<Integer> excluded = side == InvoiceType.CUSTOMER
                ? summaryProperties.getOverdueExcludedCustomerStatuses()
                : summaryProperties.getOverdueExcludedSupplierStatuses();
        return excluded.contains((int) record.getStatus());
    }

    private boolean isOverdue(SupplierDocumentsRecord record, InvoiceType type, BigDecimal paidAmount, LocalDate today) {
        if (record.getDueDate() == null) {
            return false;
        }
        BigDecimal totalDue = record.getTotalDue() == null ? BigDecimal.ZERO : BigDecimal.valueOf(record.getTotalDue());
        BigDecimal balance = totalDue.subtract(paidAmount);
        return invoiceStatusCalculator.calculate(type, paidAmount, balance, record.getDueDate(), today) == InvoiceStatus.OVERDUE;
    }

    private Map<Long, BigDecimal> allocatedAmountsByInvoiceId(List<SupplierDocumentsRecord> records) {
        List<Long> ids = records.stream().map(SupplierDocumentsRecord::getId).toList();
        return reconciliationRepository.sumAllocatedAmountsByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(InvoiceAllocatedTotal::getInvoiceId, InvoiceAllocatedTotal::getTotal));
    }
}

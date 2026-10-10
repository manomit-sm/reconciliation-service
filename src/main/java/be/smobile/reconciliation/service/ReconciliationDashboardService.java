package be.smobile.reconciliation.service;

import be.smobile.reconciliation.config.SummaryProperties;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final InvoiceTypeResolver invoiceTypeResolver;
    private final ReconciliationCandidateResolver candidateResolver;
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
     * Client feedback 2026-10-08: the cards (16 customers / 7 suppliers for 01/01-30/06/2026) still
     * didn't match the Invoices / Expenses lists. Two differences remained, both removed here, so a
     * card now counts exactly what the lists show with a red "To Be Paid" badge:
     * <ul>
     *   <li><b>unique names, not documents</b> - "multiple invoice records may belong to the same
     *   customer; the count should consider unique customer names" (same for suppliers). A
     *   document with no name at all can't be grouped, so it counts on its own;</li>
     *   <li><b>the badge's own rule</b> - the lists show "To Be Paid" in red for any unpaid
     *   document past its due date. This used to additionally drop documents by {@code status}
     *   code (see {@link SummaryProperties}) and any fully allocated to bank transactions in this
     *   module; the lists do neither (a reconciliation here doesn't touch the document, so
     *   it stays red until marked paid in the document service), so the card no longer does.</li>
     * </ul>
     * Carried over from the 2026-10-07 round: only documents whose invoice date falls in
     * {@code [start, end]} (the date the lists filter by), nothing marked paid (status 6 /
     * paid_status 1), no drafts, and the side comes from {@code crdr} (see {@link #side}).
     */
    private OverdueCounts overdueCounts(LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        Set<String> customers = new HashSet<>();
        Set<String> suppliers = new HashSet<>();
        for (SupplierDocumentsRecord record : supplierDocumentsRecordRepository.findByDueDateLessThan(today)) {
            if (!within(documentDate(record), start, end) || Boolean.TRUE.equals(record.getDraftInvoice()) || markedPaid(record)) {
                continue;
            }
            InvoiceType side = side(record);
            if (side == null || excludedStatus(record, side) || !pastDue(record, today)) {
                continue;
            }
            (side == InvoiceType.CUSTOMER ? customers : suppliers).add(counterpartyKey(record, side));
        }
        return new OverdueCounts(customers.size(), suppliers.size());
    }

    private boolean pastDue(SupplierDocumentsRecord record, LocalDate today) {
        return record.getDueDate() != null && record.getDueDate().isBefore(today);
    }

    /**
     * The name the Invoices ("Service/Client Name") / Expenses ("Supplier Name") lists group by,
     * normalised so "VDS Food" and " vds  food" are one customer. Nameless documents get a key
     * of their own so they're still counted, once each.
     */
    private String counterpartyKey(SupplierDocumentsRecord record, InvoiceType side) {
        String name = candidateResolver.partnerName(record, side == InvoiceType.CUSTOMER);
        String normalised = name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return normalised.isEmpty() ? "#" + record.getId() : normalised;
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

    /**
     * {@code crdr} first; blank falls back to {@link InvoiceTypeResolver}'s heuristic; anything
     * else (debit_note...) belongs to neither card. The one exception is a customer credit note
     * ("CN000005"): the Invoices list shows it, labelled "Debit" like an expense, with the same
     * red badge and the customer's name - so it belongs to the customer card, not the supplier one.
     */
    private InvoiceType side(SupplierDocumentsRecord record) {
        if (isCreditNote(record)) {
            return InvoiceType.CUSTOMER;
        }
        String crdr = record.getCrdr() == null ? "" : record.getCrdr().trim();
        if ("debit".equalsIgnoreCase(crdr)) {
            return InvoiceType.SUPPLIER;
        }
        if ("credit".equalsIgnoreCase(crdr)) {
            return InvoiceType.CUSTOMER;
        }
        return crdr.isEmpty() ? invoiceTypeResolver.resolve(record) : null;
    }

    private boolean isCreditNote(SupplierDocumentsRecord record) {
        String number = record.getInvoiceNumber();
        return (number != null && number.trim().regionMatches(true, 0, "CN", 0, 2))
                || (record.getCreditNoteReferenceInvoice() != null && !record.getCreditNoteReferenceInvoice().isBlank());
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
}

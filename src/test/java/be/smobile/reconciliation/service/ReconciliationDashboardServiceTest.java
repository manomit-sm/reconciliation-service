package be.smobile.reconciliation.service;

import be.smobile.reconciliation.config.SummaryProperties;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
        return service(new SummaryProperties());
    }

    private ReconciliationDashboardService service(SummaryProperties properties) {
        return new ReconciliationDashboardService(bankTransactionRepository, supplierDocumentsRecordRepository,
                new InvoiceTypeResolver(), new ReconciliationCandidateResolver(supplierDocumentsRecordRepository, reconciliationRepository), properties);
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
                .id(1L).totalDue(100.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).clientName("VDS Food").build(); // no supplierId -> customer
        SupplierDocumentsRecord overdueSupplierInvoice = SupplierDocumentsRecord.builder()
                .id(2L).totalDue(200.0).invoiceDate(SEPT).dueDate(LocalDate.of(2026, 9, 2)).supplierId(55L).supplierName("B SNCB").build();
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(overdueCustomerInvoice, overdueSupplierInvoice));

        ReconciliationSummaryResponse response = service().summary(2026, 9);

        assertEquals(1, response.summary().overdueCustomers());
        assertEquals(1, response.summary().overdueSuppliers());
    }

    private static final LocalDate SEPT = LocalDate.of(2026, 9, 10);
    private static final LocalDate H1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate H1_END = LocalDate.of(2026, 6, 30);

    private SupplierDocumentsRecord invoice(long id, String number, String client, LocalDate invoiceDate) {
        return SupplierDocumentsRecord.builder().id(id).invoiceNumber(number).crdr("credit").clientName(client).totalDue(100.0)
                .invoiceDate(invoiceDate).dueDate(invoiceDate.plusDays(1)).status((short) 0).build();
    }

    private SupplierDocumentsRecord expense(long id, String supplier, LocalDate invoiceDate) {
        return SupplierDocumentsRecord.builder().id(id).invoiceNumber("EX" + id).crdr("debit").supplierName(supplier).businessName("McDonald's Restaurants")
                .totalDue(100.0).invoiceDate(invoiceDate).dueDate(invoiceDate.plusDays(1)).status((short) 0).build();
    }

    private ReconciliationSummaryResponse.Summary summaryOf(List<SupplierDocumentsRecord> records, LocalDate start, LocalDate end) {
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(records);
        return service().summary(start, end).summary();
    }

    /**
     * Client feedback 2026-10-08, point 1: "multiple invoice records may belong to the same
     * customer; the count should consider unique customer names". The client's Invoices list for
     * 01/01-30/06/2026 has 28 red-badged sales invoices for 8 customers.
     */
    @Test
    void overdueCustomers_countUniqueCustomerNames_notInvoices() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        List<SupplierDocumentsRecord> records = List.of(
                invoice(1, "INV00001", "BP Garage", d), invoice(2, "INV00002", "BP Garage", d), invoice(3, "INV00003", "BP Garage", d),
                invoice(4, "INV00004", "VDS Food", d), invoice(5, "INV00005", "VDS Food", d),
                invoice(6, "INV00006", "Shell Garage", d),
                invoice(7, "INV00007", "January Transaction #27", d), invoice(8, "INV00008", "January Transaction #48", d));

        assertEquals(5, summaryOf(records, H1, H1_END).overdueCustomers());
    }

    @Test
    void overdueCustomers_sameNameInADifferentCaseOrSpacing_isOneCustomer() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        List<SupplierDocumentsRecord> records = List.of(
                invoice(1, "INV1", "VDS Food", d), invoice(2, "INV2", " vds  food ", d), invoice(3, "INV3", "VDS FOOD", d));

        assertEquals(1, summaryOf(records, H1, H1_END).overdueCustomers());
    }

    /** A document with no name can't be grouped with anything, so it counts on its own - not silently dropped, not merged into one. */
    @Test
    void overdueCustomers_documentsWithoutAName_countOnceEach() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        List<SupplierDocumentsRecord> records = List.of(invoice(1, "INV1", null, d), invoice(2, "INV2", "  ", d), invoice(3, "INV3", "VDS Food", d));

        assertEquals(3, summaryOf(records, H1, H1_END).overdueCustomers());
    }

    /**
     * The client's Expenses list for 01/01-30/06/2026: 12 red-badged expenses for 6 suppliers
     * (B SNCB x5, Breuwdoz, LA VIGNETTE x2, PSG Shop x2, SuperStore, Telkom) - the card must say 6.
     */
    @Test
    void overdueSuppliers_countUniqueSupplierNames_notExpenses() {
        LocalDate d = LocalDate.of(2026, 1, 29);
        List<SupplierDocumentsRecord> records = List.of(
                expense(4, "B SNCB", d), expense(5, "B SNCB", d), expense(6, "B SNCB", d), expense(7, "B SNCB", d), expense(8, "B SNCB", d),
                expense(11, "Breuwdoz Belium Sprl", d), expense(9, "LA VIGNETTE", d), expense(14, "LA VIGNETTE", d),
                expense(10, "PSG Shop", d), expense(15, "PSG Shop", d), expense(13, "SuperStore", d), expense(12, "Telkom SA SOC Ltd.", d));

        assertEquals(6, summaryOf(records, H1, H1_END).overdueSuppliers());
    }

    /** The supplier's own name, never the account's own {@code business_name}, which is the same on every expense. */
    @Test
    void overdueSuppliers_groupBySupplierName_notTheAccountsOwnBusinessName() {
        LocalDate d = LocalDate.of(2026, 1, 29);

        assertEquals(2, summaryOf(List.of(expense(1, "B SNCB", d), expense(2, "PSG Shop", d)), H1, H1_END).overdueSuppliers());
    }

    /** Paid rows (status 6 / paid_status 1, the green "Paid" badge) are not "To Be Paid". */
    @Test
    void overdueCounts_aDocumentMarkedPaidIsNotOverdue() {
        LocalDate d = LocalDate.of(2026, 1, 29);
        SupplierDocumentsRecord paidByStatus = expense(1, "NCR Voyix Belgium BV", d);
        paidByStatus.setStatus((short) 6);
        SupplierDocumentsRecord paidByFlag = expense(2, "PSG Shop", d);
        paidByFlag.setPaidStatus(1);
        SupplierDocumentsRecord unpaid = expense(3, "B SNCB", d);

        assertEquals(1, summaryOf(List.of(paidByStatus, paidByFlag, unpaid), H1, H1_END).overdueSuppliers());
    }

    /**
     * The 2026-10-07 rules dropped sales invoices by status code (0/1/2/8) and anything allocated
     * to a bank transaction; the lists show those as red "To Be Paid", so neither drops them now.
     */
    @Test
    void overdueCustomers_anyUnpaidPastDueInvoiceCounts_whateverItsStatusCode() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        List<SupplierDocumentsRecord> records = List.of(
                withStatus(invoice(1, "INV1", "A", d), 0), withStatus(invoice(2, "INV2", "B", d), 1),
                withStatus(invoice(3, "INV3", "C", d), 2), withStatus(invoice(4, "INV4", "D", d), 4), withStatus(invoice(5, "INV5", "E", d), 8));

        assertEquals(5, summaryOf(records, H1, H1_END).overdueCustomers());
    }

    private SupplierDocumentsRecord withStatus(SupplierDocumentsRecord record, int status) {
        record.setStatus((short) status);
        return record;
    }

    /** Due today is the green "0 d" badge in the client's list - not overdue yet. */
    @Test
    void overdueCustomers_dueTodayOrLater_isNotOverdue() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        SupplierDocumentsRecord dueToday = invoice(1, "INV1", "A", d);
        dueToday.setDueDate(LocalDate.now());
        SupplierDocumentsRecord dueLater = invoice(2, "INV2", "B", d);
        dueLater.setDueDate(LocalDate.now().plusDays(24));
        SupplierDocumentsRecord pastDue = invoice(3, "INV3", "C", d);
        pastDue.setDueDate(LocalDate.now().minusDays(1));
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any())).thenReturn(List.of(dueToday, dueLater, pastDue));

        assertEquals(1, service().summary(H1, H1_END).summary().overdueCustomers());
    }

    @Test
    void overdueCounts_draftInvoice_isExcluded() {
        SupplierDocumentsRecord draft = invoice(1, "INV1", "A", SEPT);
        draft.setDraftInvoice(true);

        assertEquals(0, summaryOf(List.of(draft), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).overdueCustomers());
    }

    /** The Invoices list shows credit notes ("CN000005", labelled Debit); they're the customer's, not a supplier's. */
    @Test
    void overdueCounts_aCreditNoteCountsForItsCustomer_notAsASupplier() {
        LocalDate d = LocalDate.of(2026, 1, 28);
        SupplierDocumentsRecord creditNote = SupplierDocumentsRecord.builder().id(9L).invoiceNumber("CN000005").crdr("debit").clientName("BP Garage")
                .totalDue(121.0).invoiceDate(d).dueDate(d.plusDays(1)).build();
        SupplierDocumentsRecord invoice = invoice(1, "INV00007", "BP Garage", d);

        ReconciliationSummaryResponse.Summary summary = summaryOf(List.of(creditNote, invoice), H1, H1_END);

        assertEquals(1, summary.overdueCustomers());
        assertEquals(0, summary.overdueSuppliers());
    }

    @Test
    void overdueCounts_onlyCountDocumentsDatedInsideTheSelectedRange() {
        List<SupplierDocumentsRecord> records = List.of(
                invoice(1, "INV1", "A", LocalDate.of(2026, 5, 28)),
                invoice(2, "INV2", "B", LocalDate.of(2025, 11, 3)),   // outside the range
                expense(4, "B SNCB", LocalDate.of(2026, 1, 29)),
                expense(5, "PSG Shop", LocalDate.of(2025, 12, 31)));  // outside the range

        ReconciliationSummaryResponse.Summary inRange = summaryOf(records, H1, LocalDate.of(2026, 10, 31));
        assertEquals(1, inRange.overdueCustomers());
        assertEquals(1, inRange.overdueSuppliers());
    }

    @Test
    void overdueCounts_changeWhenTheRangeChanges() {
        List<SupplierDocumentsRecord> records = List.of(
                invoice(1, "INV1", "A", LocalDate.of(2026, 5, 28)),
                expense(2, "B SNCB", LocalDate.of(2026, 1, 29)));

        assertEquals(1, summaryOf(records, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)).overdueCustomers());
        assertEquals(0, summaryOf(records, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)).overdueSuppliers());
        assertEquals(0, summaryOf(records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).overdueCustomers());
        assertEquals(1, summaryOf(records, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)).overdueSuppliers());
    }

    @Test
    void overdueCounts_otherDocumentTypesBelongToNeitherCard() {
        assertEquals(0, summaryOf(List.of(withCrdr(invoice(1, "X1", "A", SEPT), "debit_note")), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).overdueCustomers());
    }

    private SupplierDocumentsRecord withCrdr(SupplierDocumentsRecord record, String crdr) {
        record.setCrdr(crdr);
        return record;
    }

    @Test
    void overdueCounts_theExcludedStatusesAreStillConfigurable() {
        SummaryProperties properties = new SummaryProperties();
        properties.setOverdueExcludedCustomerStatuses(List.of(3));
        when(supplierDocumentsRecordRepository.findByDueDateLessThan(any()))
                .thenReturn(List.of(withStatus(invoice(1, "INV1", "A", SEPT), 3), withStatus(invoice(2, "INV2", "B", SEPT), 1)));

        assertEquals(1, service(properties).summary(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).summary().overdueCustomers());
    }
}

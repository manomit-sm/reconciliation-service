package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the {@link MatchCandidate} list {@link be.smobile.reconciliation.matching.MatchingEngine}
 * scores a {@link BankTransaction} against, from the real {@link SupplierDocumentsRecord} table.
 * <p>
 * <b>Best-effort guess, flagged clearly (2026-09-13), same pattern as the tenant claim guess</b>:
 * {@code SupplierDocumentsRecord} has no confirmed column that reliably says "this is a
 * customer invoice" vs. "this is a supplier invoice" (see {@code InvoiceType}'s own javadoc).
 * This resolver stands in for that with the transaction's own direction - CREDIT (money coming
 * in) is assumed to mean the payer is a customer, DEBIT (money going out) that the payee is a
 * supplier - purely to decide which name column to read as {@code MatchCandidate.partnerName}
 * ({@code clientName} vs {@code businessName}). It deliberately does <b>not</b> use this to
 * exclude rows from the candidate set - since there's no confirmed discriminator to exclude by
 * either, a supplier invoice row's genuinely irrelevant {@code clientName} on a CREDIT
 * transaction just won't equal the transaction's counterparty, so
 * {@link be.smobile.reconciliation.matching.RuleBasedMatchingEngine}'s own PARTNER check
 * ends up doing the real filtering - not this class.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationCandidateResolver {

    /** Recency window for {@link SupplierDocumentsRecordRepository#findByDueDateGreaterThanEqualOrDueDateIsNull} - see that method's javadoc. */
    private static final int CANDIDATE_WINDOW_MONTHS = 6;

    private final SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    private final ReconciliationRepository reconciliationRepository;

    public List<MatchCandidate> resolveCandidates(BankTransaction transaction) {
        return resolveCandidates(List.of(transaction)).getOrDefault(transaction.getId(), List.of());
    }

    /**
     * Same result as calling {@link #resolveCandidates(BankTransaction)} once per transaction,
     * but the invoice table and the allocated-totals table are each read once for the whole
     * batch (from the earliest transaction's recency cutoff) instead of once per transaction -
     * what makes re-matching a month's worth of unmatched transactions on every dashboard load
     * affordable. Each transaction still only sees invoices inside its <i>own</i> window: the
     * batch-wide query is a superset, narrowed again per transaction below.
     */
    public Map<UUID, List<MatchCandidate>> resolveCandidates(List<BankTransaction> transactions) {
        if (transactions.isEmpty()) {
            return Map.of();
        }
        LocalDate earliestCutoff = transactions.stream()
                .map(tx -> cutoffFor(tx))
                .min(LocalDate::compareTo)
                .orElseThrow();
        List<SupplierDocumentsRecord> records = supplierDocumentsRecordRepository.findByDueDateGreaterThanEqualOrDueDateIsNull(earliestCutoff);
        if (records.isEmpty()) {
            return transactions.stream().collect(Collectors.toMap(BankTransaction::getId, tx -> List.<MatchCandidate>of(), (a, b) -> a));
        }

        Map<Long, BigDecimal> alreadyAllocated = allocatedAmountsByInvoiceId(records);

        Map<UUID, List<MatchCandidate>> result = new HashMap<>();
        for (BankTransaction transaction : transactions) {
            LocalDate cutoff = cutoffFor(transaction);
            boolean treatAsCustomerInvoice = treatAsCustomerInvoice(transaction);
            result.put(transaction.getId(), records.stream()
                    .filter(record -> record.getDueDate() == null || !record.getDueDate().isBefore(cutoff))
                    .filter(record -> directionCompatible(record, transaction))
                    .map(record -> toCandidate(record, alreadyAllocated.getOrDefault(record.getId(), BigDecimal.ZERO), treatAsCustomerInvoice))
                    .filter(candidate -> candidate.remainingBalance().signum() > 0)
                    .toList());
        }
        return result;
    }

    /** The date the document is dated: {@code invoiceDate}, else the generic {@code date} column - the client's UI shows these as the same "Invoice Date". */
    private LocalDate invoiceDate(SupplierDocumentsRecord record) {
        return record.getInvoiceDate() != null ? record.getInvoiceDate() : record.getDate();
    }

    /**
     * Identifiers other than the invoice number that a payer might quote: the document's own
     * {@code reference} (the "Transaction reference" typed on an invoice - client feedback
     * 2026-10-02, point 5) and its order number. Blanks dropped.
     */
    private List<String> extraReferences(SupplierDocumentsRecord record) {
        return java.util.stream.Stream.of(record.getReference(), record.getOrderNumber())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * Money out of the account can only pay an expense/supplier invoice, money in can only be a
     * customer's payment of a sales invoice - without this, the exact-amount search (which
     * ignores partner names) could pair a payment with a document of the opposite direction that
     * happens to have the same amount (bank amounts are stored positive either way). Evidence
     * for {@code crdr}'s values is the client's own UI: the expense list shows "Debit" for every
     * row and the invoice list "Credit", and every expense payload they sent has
     * {@code "crdr":"debit"}. Only a record explicitly flagged as the <i>opposite</i> direction is
     * excluded - blank or any other value ("debit_note", ...) stays a candidate, as before.
     */
    private boolean directionCompatible(SupplierDocumentsRecord record, BankTransaction transaction) {
        String crdr = record.getCrdr() == null ? "" : record.getCrdr().trim();
        return transaction.getDirection() == TransactionDirection.CREDIT
                ? !"debit".equalsIgnoreCase(crdr)
                : !"credit".equalsIgnoreCase(crdr);
    }

    private LocalDate cutoffFor(BankTransaction transaction) {
        return transaction.getTransactionDate().minusMonths(CANDIDATE_WINDOW_MONTHS);
    }

    private Map<Long, BigDecimal> allocatedAmountsByInvoiceId(List<SupplierDocumentsRecord> records) {
        List<Long> ids = records.stream().map(SupplierDocumentsRecord::getId).toList();
        return reconciliationRepository.sumAllocatedAmountsByInvoiceIds(ids).stream()
                .collect(Collectors.toMap(InvoiceAllocatedTotal::getInvoiceId, InvoiceAllocatedTotal::getTotal));
    }

    private MatchCandidate toCandidate(SupplierDocumentsRecord record, BigDecimal alreadyAllocated, boolean treatAsCustomerInvoice) {
        // totalDue is a Double on this entity (see its javadoc) - BigDecimal.valueOf(double), never `new
        // BigDecimal(double)`, so the conversion goes through the value's decimal string representation
        // rather than its exact (and misleading) binary form.
        BigDecimal totalDue = record.getTotalDue() == null ? BigDecimal.ZERO : BigDecimal.valueOf(record.getTotalDue());
        BigDecimal remainingBalance = totalDue.subtract(alreadyAllocated);
        return new MatchCandidate(record.getId(), InvoiceNumbers.of(record), partnerName(record, treatAsCustomerInvoice), record.getDueDate(), remainingBalance,
                invoiceDate(record), extraReferences(record));
    }

    /**
     * Public so callers building a REST response (e.g. {@code BankTransactionDtoMapper}'s
     * {@code matching_criteria}/invoice {@code type} text) can apply the exact same heuristic
     * this class uses internally, rather than re-deriving it - see class javadoc for the caveat.
     */
    public boolean treatAsCustomerInvoice(BankTransaction transaction) {
        return transaction.getDirection() == TransactionDirection.CREDIT;
    }

    /**
     * Public so {@code ReconciliationService} can label an already-confirmed
     * {@link be.smobile.reconciliation.entity.Reconciliation}'s invoice the same way, for the
     * "Matched to:" (RECONCILED) view - same heuristic, same caveat, see class javadoc.
     */
    public String partnerName(SupplierDocumentsRecord record, boolean treatAsCustomerInvoice) {
        return treatAsCustomerInvoice ? customerName(record) : supplierName(record);
    }

    /**
     * Client feedback 2026-09-30 ("partner and reference are always zero"): {@code clientName}
     * is the only field here that actually identifies a customer. {@code firstName}/{@code
     * lastName} looked like a plausible fallback, but the client's own test-environment data
     * (an "expense" record for McDonald's Restaurants, paid to NCR Voyix Belgium BV) shows
     * {@code firstName: "Tamara", lastName: "Davis"} identically on <i>every</i> row regardless
     * of who the actual counterparty is - the account owner's own name, not a customer's. Using
     * it here meant a bank description that matched the real customer exactly still scored
     * PARTNER = 0 whenever {@code clientName} happened to be blank, and, worse, could produce a
     * false match against the account owner's own name on an unrelated transaction. No longer
     * used; a blank {@code clientName} now correctly means "no partner name available" rather
     * than falling back to something that was never the counterparty.
     */
    private String customerName(SupplierDocumentsRecord record) {
        return record.getClientName() == null ? "" : record.getClientName().trim();
    }

    /**
     * Same bug, the supplier side, and the one actually reproduced against the client's data
     * (2026-09-30): this used to try {@code businessName} <i>before</i> {@code supplierName}.
     * {@code businessName} is the account's own registered business ("McDonald's Restaurants")
     * and is populated on every row, supplier or not - so {@code supplierName} (the real
     * counterparty, e.g. "NCR Voyix Belgium BV") was never even read, and PARTNER matching was
     * comparing the bank transaction's description against the account holder's own name
     * instead of the actual supplier's. Confirmed live: a bank transaction described exactly
     * "NCR VOYIX BELGIUM BV" against that same expense still scored PARTNER = 0 before this fix.
     * {@code businessName} is kept only as the last resort for the (currently unseen) case where
     * {@code supplierName} itself is blank.
     */
    private String supplierName(SupplierDocumentsRecord record) {
        return firstNonBlank(record.getSupplierName(), record.getBusinessName());
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback == null ? "" : fallback;
    }
}

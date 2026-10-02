package be.smobile.reconciliation.status;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.springframework.stereotype.Component;

/**
 * Best-effort {@link InvoiceType} classifier for a {@link SupplierDocumentsRecord} <b>on its
 * own</b>, with no bank transaction to borrow a direction signal from (see
 * {@code ReconciliationCandidateResolver}'s own {@code treatAsCustomerInvoice}, which does have
 * one and is the better signal wherever a transaction is actually in hand - this class exists
 * for the cases that don't, e.g. API 1's per-invoice dashboard counts and API 4/5's
 * standalone invoice summaries).
 * <p>
 * <b>Heuristic, not confirmed, 2026-09-16</b>: same unresolved-discriminator situation as
 * {@link InvoiceType}'s own javadoc already describes (no confirmed customer-vs-supplier column)
 * - {@code supplierId} is used here because it is the one candidate column that is actually
 * nullable with no default (unlike {@code clientid}, which is {@code NOT NULL DEFAULT 5} on
 * every row per {@code SupplierDocumentsRecord}'s DDL and so can't discriminate anything): a
 * non-null {@code supplierId} is read as "this document is against a supplier" (expense/supplier
 * invoice), its absence as a customer invoice. Reasoned from the column's own nullability, not a
 * blind guess - but still unconfirmed, same as the transaction-direction heuristic it parallels.
 */
@Component
public class InvoiceTypeResolver {

    /**
     * {@code crdr} first (client feedback 2026-10-02): the client's UI lists expenses as "Debit"
     * and sales invoices as "Credit" and every expense payload they sent has {@code "crdr":"debit"},
     * whereas {@code supplierId} is <i>null</i> on the new expenses they create (record 72, an
     * EXPENSE) - so the heuristic below alone called those customer invoices. Any other
     * {@code crdr} value ("debit_note", blank, ...) falls back to the {@code supplierId} guess.
     */
    public InvoiceType resolve(SupplierDocumentsRecord record) {
        String crdr = record.getCrdr() == null ? "" : record.getCrdr().trim();
        if ("debit".equalsIgnoreCase(crdr)) {
            return InvoiceType.SUPPLIER;
        }
        if ("credit".equalsIgnoreCase(crdr)) {
            return InvoiceType.CUSTOMER;
        }
        return record.getSupplierId() != null ? InvoiceType.SUPPLIER : InvoiceType.CUSTOMER;
    }
}

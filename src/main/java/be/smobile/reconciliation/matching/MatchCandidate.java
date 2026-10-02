package be.smobile.reconciliation.matching;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A lean, engine-facing view of one open invoice being considered as a match for a
 * {@link be.smobile.reconciliation.entity.BankTransaction} - deliberately not the
 * {@link be.smobile.reconciliation.entity.SupplierDocumentsRecord} entity itself, so
 * {@link RuleBasedMatchingEngine} stays a pure function over simple values and is directly
 * unit-testable without constructing that entity's ~80 columns.
 * <p>
 * Building this from a real {@code SupplierDocumentsRecord} (partner name = supplierName for a
 * supplier invoice / clientName for a customer invoice, {@code remainingBalance} = totalDue
 * minus the sum of this invoice's existing {@code Reconciliation.allocatedAmount} rows) is the
 * caller's (service layer's) job, not this package's.
 *
 * @param invoiceId        {@code SupplierDocumentsRecord.id}
 * @param invoiceNumber    for the "Invoice reference detected" criterion
 * @param partnerName      customer/supplier name, for the "Customer/Supplier matches" criterion
 * @param dueDate          for the "Date Similarity" criterion
 * @param remainingBalance amount still open on this invoice (totalDue - already allocated) -
 *                          never the invoice's original total, so a partially-paid invoice
 *                          (the N:1 "second installment" case) is matched against what's
 *                          actually still owed
 * @param invoiceDate      the date the invoice/expense is dated - also for "Date Similarity" (a
 *                          payment usually lands near either this or {@code dueDate}), and to
 *                          tell a <i>future-dated</i> invoice (dated after the payment, so very
 *                          unlikely to be what was paid) from a past one. May be null.
 * @param references       further identifiers that may appear in a bank transaction's text -
 *                          the document's own {@code reference} and its order number - checked
 *                          alongside {@code invoiceNumber} for the "Invoice reference detected"
 *                          criterion. Never null.
 */
public record MatchCandidate(
        Long invoiceId,
        String invoiceNumber,
        String partnerName,
        LocalDate dueDate,
        BigDecimal remainingBalance,
        LocalDate invoiceDate,
        List<String> references
) {

    public MatchCandidate {
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** The original five-field form - no invoice date, no extra references. */
    public MatchCandidate(Long invoiceId, String invoiceNumber, String partnerName, LocalDate dueDate, BigDecimal remainingBalance) {
        this(invoiceId, invoiceNumber, partnerName, dueDate, remainingBalance, null, List.of());
    }
}

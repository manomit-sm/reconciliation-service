package be.smobile.reconciliation.matching;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A lean, engine-facing view of one open invoice being considered as a match for a
 * {@link be.smobile.reconciliation.entity.BankTransaction} - deliberately not the
 * {@link be.smobile.reconciliation.entity.SupplierDocumentsRecord} entity itself, so
 * {@link RuleBasedMatchingEngine} stays a pure function over simple values and is directly
 * unit-testable without constructing that entity's ~80 columns.
 * <p>
 * Building this from a real {@code SupplierDocumentsRecord} (partner name = businessName for a
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
 */
public record MatchCandidate(
        Long invoiceId,
        String invoiceNumber,
        String partnerName,
        LocalDate dueDate,
        BigDecimal remainingBalance
) {
}

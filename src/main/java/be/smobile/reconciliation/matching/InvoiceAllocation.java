package be.smobile.reconciliation.matching;

import java.math.BigDecimal;

/**
 * Part of a {@link MatchSuggestion}: "allocate {@code amount} of this transaction to
 * {@code invoiceId}". One instance per row that would become a
 * {@link be.smobile.reconciliation.entity.Reconciliation} if the suggestion is confirmed - see
 * section 8 of the design doc ("BT001 -> INV001 -> 250 EUR").
 */
public record InvoiceAllocation(Long invoiceId, BigDecimal amount) {
}

package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The number to show (and to look for in a bank description) for an invoice/expense record.
 * Imported expenses have {@code invoice_number = ""} - the supplier's own number was never
 * captured - and carry the system-generated {@code reference} instead (e.g.
 * {@code EX2026092813011110}, seen in the client's test-environment data 2026-09-28). Without
 * this fallback such records showed a blank {@code invoice_number} in the UI and could never
 * satisfy the matching engine's "invoice reference detected" criterion.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InvoiceNumbers {

    /** {@code invoiceNumber} if it has text, else {@code reference} if it has text, else {@code null}. */
    public static String of(SupplierDocumentsRecord record) {
        if (record.getInvoiceNumber() != null && !record.getInvoiceNumber().isBlank()) {
            return record.getInvoiceNumber();
        }
        if (record.getReference() != null && !record.getReference().isBlank()) {
            return record.getReference();
        }
        return null;
    }
}

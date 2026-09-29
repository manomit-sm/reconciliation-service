package be.smobile.reconciliation.model.enums;

/**
 * Whether a {@code SupplierDocumentsRecord} being reconciled is money owed <b>to</b> the
 * company (customer invoice) or money owed <b>by</b> the company (supplier invoice). See
 * section 2 of the design doc - the two share the same due-date/payment computation but use
 * different labels for the "open" state (customer: "To Be Received", supplier: "To Be Paid").
 * <p>
 * Not derived automatically from {@code SupplierDocumentsRecord} - that entity's
 * {@code clientid}/{@code supplierId}/{@code crdr}/{@code invoiceType} fields don't have a
 * confirmed customer-vs-supplier rule yet, so {@link be.smobile.reconciliation.status.InvoiceStatusCalculator}
 * takes this as an explicit parameter instead of guessing at one.
 */
public enum InvoiceType {
    CUSTOMER,
    SUPPLIER
}

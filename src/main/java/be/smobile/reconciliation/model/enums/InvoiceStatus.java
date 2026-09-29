package be.smobile.reconciliation.model.enums;

/**
 * Union of the supplier-invoice and customer-invoice status lists from section 2 of the
 * design doc:
 * <ul>
 *     <li>Supplier: DRAFT, APPROVED, TO_BE_PAID, DUE_TODAY, OVERDUE, PARTIALLY_PAID, PAID, CANCELLED</li>
 *     <li>Customer: DRAFT, SENT, TO_BE_RECEIVED, DUE_TODAY, OVERDUE, PARTIALLY_PAID, PAID, CANCELLED</li>
 * </ul>
 * {@code DRAFT}, {@code APPROVED}, {@code SENT} and {@code CANCELLED} are workflow states set
 * by user action elsewhere (invoice creation/approval/cancellation - out of scope for this
 * milestone). The remaining statuses ({@code TO_BE_PAID}/{@code TO_BE_RECEIVED},
 * {@code DUE_TODAY}, {@code OVERDUE}, {@code PARTIALLY_PAID}, {@code PAID}) are computed
 * automatically by {@link be.smobile.reconciliation.status.InvoiceStatusCalculator} per
 * section 9 and must never be edited manually.
 */
public enum InvoiceStatus {
    DRAFT,
    APPROVED,
    SENT,
    TO_BE_PAID,
    TO_BE_RECEIVED,
    DUE_TODAY,
    OVERDUE,
    PARTIALLY_PAID,
    PAID,
    CANCELLED
}

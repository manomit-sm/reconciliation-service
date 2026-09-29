package be.smobile.reconciliation.status;

import be.smobile.reconciliation.model.enums.BadgeColor;

/**
 * A status as it should be displayed in the UI, e.g. "To Be Paid 14d" / Green, "Overdue 3d" /
 * Red, "Due Today" / Orange, "Paid" / Green - see section 2 of the design doc.
 */
public record InvoiceStatusBadge(String label, BadgeColor color) {
}

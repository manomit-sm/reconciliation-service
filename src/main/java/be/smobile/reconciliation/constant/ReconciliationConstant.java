package be.smobile.reconciliation.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReconciliationConstant {

    public static final String PAID = "Paid";
    public static final String PARTIALLY_PAID = "Partially Paid";
    public static final String DUE_TODAY = "Due Today";
    public static final String OVERDUE = "Overdue";
    public static final String TO_BE_PAID = "To Be Paid";
    public static final String TO_BE_RECEIVED = "To Be Received";
    public static final String DRAFT = "Draft";
    public static final String APPROVED = "Approved";
    public static final String SENT = "Sent";
    public static final String CANCELLED = "Cancelled";
}

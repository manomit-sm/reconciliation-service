package be.smobile.reconciliation.model.enums;

/**
 * Badge colors from section 2's "Status Computation" examples (Green = to be paid/received or
 * paid, Orange = due today, Red = overdue). GRAY covers the workflow states
 * (draft/approved/sent/cancelled) that section 2 doesn't assign a color to.
 */
public enum BadgeColor {
    GREEN,
    ORANGE,
    RED,
    GRAY
}

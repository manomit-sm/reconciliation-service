package be.smobile.reconciliation.model.enums;

/**
 * The status icons from the Reconciliation Screen's left panel (section 4): 🔴 UNMATCHED,
 * 🟡 SUGGESTED_MATCH, 🟢 RECONCILED - plus {@link #NEEDS_REVIEW}, added 2026-09-16 per the
 * client's "Reconciliation Feature – Frontend API Requirements" doc (section 2): "The
 * transaction requires manual review or has been flagged by a user." Set by API 8 (Mark
 * Transaction for Review) and cleared back to {@link #UNMATCHED} by API 6 (Reopen).
 * <p>
 * Wire value for the frontend's {@code reconciliation_status} field is this constant's name
 * lowercased ({@code unmatched}/{@code suggested_match}/{@code needs_review}/{@code reconciled})
 * - see {@link #wireValue()} - never the DB storage representation, which stays the uppercase
 * enum name via {@code @Enumerated(EnumType.STRING)} (unchanged, so no migration was needed to
 * add this constant - {@code reconciliation_status} is already a plain {@code VARCHAR(32)}).
 */
public enum BankTransactionStatus {
    UNMATCHED,
    SUGGESTED_MATCH,
    NEEDS_REVIEW,
    RECONCILED;

    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

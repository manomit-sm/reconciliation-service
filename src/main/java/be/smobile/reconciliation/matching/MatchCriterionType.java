package be.smobile.reconciliation.matching;

/**
 * The four weighted criteria from section 7 of the design doc ("Matching Engine"):
 * Amount Match 50%, Partner Match 30%, Invoice Reference 10%, Date Similarity 10%. Matches the
 * checklist shown in the "Suggested match" screen ("Amount matches exactly" / "Customer
 * matches" / "Invoice reference detected").
 */
public enum MatchCriterionType {
    AMOUNT,
    PARTNER,
    REFERENCE,
    DATE
}

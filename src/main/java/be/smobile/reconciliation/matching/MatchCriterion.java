package be.smobile.reconciliation.matching;

/** Whether one weighted criterion (see {@link MatchCriterionType}) was satisfied. */
public record MatchCriterion(MatchCriterionType type, boolean met) {
}

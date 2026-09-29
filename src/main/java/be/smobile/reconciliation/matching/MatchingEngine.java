package be.smobile.reconciliation.matching;

import be.smobile.reconciliation.entity.BankTransaction;

import java.util.List;

/**
 * Section 6/7 of the design doc: "Rule-based matching/confidence engine". {@code candidates}
 * is expected to already be a reasonably-scoped open-invoice list (section 10's "Better
 * Architecture" candidate-filtering guidance still applies even without AI: same
 * customer/supplier, remainingBalance > 0, recent - filtering that is the caller's job via
 * repository queries, not this interface's).
 * <p>
 * One implementation only for Milestone 2 - {@link RuleBasedMatchingEngine} - kept behind this
 * interface so a future Phase 3 AI-backed implementation (see the milestone-2 chat discussion
 * about the client's "AI agent" remark) can be swapped in later without changing any caller.
 */
public interface MatchingEngine {

    /**
     * @param transaction the bank transaction being reconciled
     * @param candidates  open invoices to consider - see class javadoc
     * @return the best suggestion found; {@link MatchSuggestion#none()} if nothing viable
     */
    MatchSuggestion suggest(BankTransaction transaction, List<MatchCandidate> candidates);
}

package be.smobile.reconciliation.matching;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Section 7 ("Matching Engine") of the design doc gives the four criteria weights (50/30/10/10)
 * and the confidence-band thresholds (90/70) as fixed numbers, with no tuning mechanism
 * described - these are exposed as config here (not hardcoded constants) purely so they can be
 * adjusted without a redeploy if the client's real data needs it, not because the doc implies
 * they should differ per environment.
 * <p>
 * {@code dateToleranceDays} and {@code amountTolerance} are NOT in the design doc at all - the
 * doc's own weight table has no partial-credit curve, and given the client's confirmation that
 * Milestone 2 is "solely a rule based engine, total arithmetic calculation", each criterion
 * here is scored as a plain pass/fail (see {@link RuleBasedMatchingEngine}) rather than a
 * graduated percentage - these two values are the only knobs needed to decide what counts as
 * "close enough" to pass. Best-effort defaults, not confirmed with the client.
 */
@ConfigurationProperties(prefix = "app.matching")
@Getter
@Setter
public class MatchingProperties {

    /**
     * When true, a suggestion whose AMOUNT criterion is met is classified as
     * {@code suggested_match} even if its total score is below {@link #suggestedThreshold} -
     * client feedback 2026-09-28: "The reconciliation engine should automatically detect and
     * display expense records that match the transaction amount under the Suggested Match tab."
     * An amount-only match scores just {@link #amountWeight}. Set false to go back to the pure
     * score-based bands.
     */
    private boolean suggestOnExactAmount = true;

    /** Weight of the "Amount matches exactly" criterion - section 7. */
    private int amountWeight = 50;

    /** Weight of the "Customer/Supplier matches" criterion - section 7. */
    private int partnerWeight = 30;

    /** Weight of the "Invoice reference detected" criterion - section 7. */
    private int referenceWeight = 10;

    /** Weight of the "Date Similarity" criterion - section 7. */
    private int dateWeight = 10;

    /** Confidence >= this counts as "Suggested Match" (section 7's 70-89/90-100 bands combined - see class javadoc). */
    private int suggestedThreshold = 70;

    /**
     * Confidence >= this skips the manual "Approve match" step entirely and reconciles the
     * transaction automatically - the client's 2026-09-19 instruction: "When the score is 98%
     * or more, set the status as Paid and this transaction will not be reconciled manual."
     * ("Paid" is read as this module's own {@code reconciled} status - the doc has no separate
     * transaction-level "Paid" state, only an invoice-level one; see
     * {@link be.smobile.reconciliation.model.enums.BankTransactionStatus}.) Given the four
     * criteria weights (50/30/10/10) only ever sum to 30/40/50/60/70/80/90/100, 98 in practice
     * means "every criterion matched" (100) - deliberately left as 98, not hardcoded to 100, so
     * the client can loosen it later without a code change. See
     * {@link be.smobile.reconciliation.service.ReconciliationService#classify}.
     */
    private int autoReconcileThreshold = 98;

    /**
     * Confidence >= this is section 7's "Auto Suggest" band, vs. "Review Required" below it.
     * Not currently used to change any behavior (every suggestion still requires the user to
     * press Confirm per the UI mockups - see {@code TenantFilter}-adjacent milestone-2 chat
     * history) - kept only so the UI can label a suggestion accordingly if the client wants
     * that distinction shown.
     */
    private int autoSuggestThreshold = 90;

    /** How many days apart a transaction and an invoice's due date may be for the DATE criterion to still count as met. */
    private int dateToleranceDays = 7;

    /** Max candidate invoices considered per grouping search (section 10's own guidance: "10-50 candidate invoices... not 50,000"), to keep the subset-sum search bounded. */
    private int maxCandidatesForGrouping = 20;
}

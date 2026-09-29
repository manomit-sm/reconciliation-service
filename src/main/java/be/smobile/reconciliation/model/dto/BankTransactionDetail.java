package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API 4 ("Get Bank Transaction Details") response - a single shape covering the doc's three
 * per-status example variants (4.1 reconciled / 4.2 needs_review / 4.3 suggested_match), rather
 * than three different response shapes off one endpoint: whichever fields don't apply to this
 * transaction's actual status are {@code null} and, via {@link JsonInclude}, simply omitted from
 * the JSON - a caller checking {@code reconciliation_status} first (as the doc's own three
 * sub-sections are organized) sees exactly the fields that section documents, no extras. Also
 * reused as-is for API 7 ("Approve Match/Reconcile") and API 8 ("Mark Transaction for Review")
 * responses - both say "Return the updated transaction information", and this is that.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Full detail for one bank transaction. Fields under 'reconciled/needs_review' or 'suggested_match' below are "
        + "present only when they apply to this transaction's current reconciliation_status; the rest are omitted from the response.")
public record BankTransactionDetail(
        @Schema(description = "Bank transaction id") UUID id,
        @Schema(description = "Pilim bank account this transaction belongs to") @JsonProperty("account_id") UUID accountId,
        @Schema(description = "Name of the bank", example = "Belfius") @JsonProperty("bank_name") String bankName,
        @Schema(description = "Bank statement this transaction was imported from, if any") @JsonProperty("statement_id") UUID statementId,
        @Schema(description = "How this transaction entered the system", allowableValues = {"pdf", "ponto", "manual"}) @JsonProperty("source_type") String sourceType,
        @Schema(description = "Transaction id assigned by the source system (e.g. Ponto), if any") @JsonProperty("external_transaction_id") String externalTransactionId,
        @Schema(description = "Date the transaction occurred") @JsonProperty("transaction_date") LocalDate transactionDate,
        @Schema(description = "Free-text bank description/label") String description,
        @Schema(description = "Structured payment reference, if present") String reference,
        @Schema(description = "Transaction amount, always positive") BigDecimal amount,
        @Schema(description = "Money flow direction", allowableValues = {"credit", "debit"}) String direction,
        @Schema(description = "Current reconciliation status", allowableValues = {"unmatched", "suggested_match", "needs_review", "reconciled"})
        @JsonProperty("reconciliation_status") String reconciliationStatus,

        // 4.1 (reconciled) / 4.2 (needs_review)
        @Schema(description = "Full name of who confirmed or flagged this transaction (falls back to their user id for records written before names were captured). Present when status is reconciled or needs_review.", example = "Prince Boghara")
        @JsonProperty("reviewed_by") String reviewedBy,
        @Schema(description = "Reviewer's free-text comment. Present when status is reconciled or needs_review.")
        @JsonProperty("reviewer_comment") String reviewerComment,
        @Schema(description = "When this transaction was reconciled (reconciled) or flagged for review (needs_review).")
        @JsonProperty("reconciliation_date") Instant reconciliationDate,
        @Schema(description = "Invoices this transaction was reconciled against. Present when status is reconciled.")
        @JsonProperty("matched_invoices") List<InvoiceSummary> matchedInvoices,

        // 4.3 (suggested_match)
        @Schema(description = "System-computed match confidence, 0-100. Present when status is suggested_match.")
        @JsonProperty("match_score") Integer matchScore,
        @Schema(description = "Human-readable list of criteria that contributed to the match score. Present when status is suggested_match.")
        @JsonProperty("matching_criteria") List<String> matchingCriteria,
        @Schema(description = "Per-criterion point breakdown of the match score. Present when status is suggested_match.")
        @JsonProperty("scoring_details") List<ScoringDetail> scoringDetails,
        @Schema(description = "This transaction's own amount, repeated for convenience. Present when status is suggested_match.")
        @JsonProperty("payment_amount") BigDecimal paymentAmount,
        @Schema(description = "Total amount that would be allocated to the suggested invoice(s). Present when status is suggested_match.")
        @JsonProperty("allocated_amount") BigDecimal allocatedAmount,

        // Multi-payment allocation (see MultiPaymentAllocationService's javadoc) - not part of the formal API doc.
        @Schema(description = "A suggested cross-payment/cross-invoice allocation this transaction is part of, if any. See "
                + "MultiPaymentAllocationService. Present only when status is unmatched or suggested_match and such a group was detected.")
        @JsonProperty("multi_payment_allocation") MultiPaymentAllocation multiPaymentAllocation,

        @Schema(description = "When this transaction was first recorded") @JsonProperty("created_at") Instant createdAt,
        @Schema(description = "When this transaction was last modified") @JsonProperty("updated_at") Instant updatedAt) {
}

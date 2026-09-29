package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** API 3 ("Get Bank Transactions") list item - field names/casing match that section's example verbatim. */
@Schema(description = "One bank transaction, as shown in the transactions list.")
public record BankTransactionSummary(
        @Schema(description = "Bank transaction id") UUID id,
        @Schema(description = "Pilim bank account this transaction belongs to") @JsonProperty("account_id") UUID accountId,
        @Schema(description = "Bank statement this transaction was imported from, if any") @JsonProperty("statement_id") UUID statementId,
        @Schema(description = "How this transaction entered the system", allowableValues = {"pdf", "ponto", "manual"}) @JsonProperty("source_type") String sourceType,
        @Schema(description = "Name of the bank", example = "Belfius") @JsonProperty("bank_name") String bankName,
        @Schema(description = "Transaction id assigned by the source system (e.g. Ponto), if any") @JsonProperty("external_transaction_id") String externalTransactionId,
        @Schema(description = "Date the transaction occurred") @JsonProperty("transaction_date") LocalDate transactionDate,
        @Schema(description = "Free-text bank description/label") String description,
        @Schema(description = "Structured payment reference, if present") String reference,
        @Schema(description = "Transaction amount, always positive") BigDecimal amount,
        @Schema(description = "Money flow direction", allowableValues = {"credit", "debit"}) String direction,
        @Schema(description = "Current reconciliation status", allowableValues = {"unmatched", "suggested_match", "needs_review", "reconciled"})
        @JsonProperty("reconciliation_status") String reconciliationStatus,
        @Schema(description = "Full name of who reconciled or flagged this transaction. Present only for reconciled and needs_review transactions.", example = "Prince Boghara")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reviewed_by") String reviewedBy,
        @Schema(description = "Reviewer's comment. Present only for needs_review transactions that have one.", example = "Wrong amount")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reviewer_comment") String reviewerComment,
        @Schema(description = "Invoices this transaction was reconciled against. Present only for reconciled transactions.")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("matched_invoices") List<InvoiceSummary> matchedInvoices,
        @Schema(description = "When this transaction was first recorded") @JsonProperty("created_at") Instant createdAt,
        @Schema(description = "When this transaction was last modified") @JsonProperty("updated_at") Instant updatedAt,
        @Schema(description = "When it was reconciled (reconciled) or flagged for review (needs_review). Absent for other statuses.")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("reconciliation_date") Instant reconciliationDate) {
}

package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** API 1 ("Get Reconciliation Summary") response - field names/casing match that section's example verbatim. */
@Schema(description = "Dashboard counters for one calendar month.")
public record ReconciliationSummaryResponse(
        @Schema(description = "Calendar year", example = "2026") int year,
        @Schema(description = "Calendar month (1-12)", example = "9") int month,
        Summary summary) {

    @Schema(description = "Per-status transaction counts and overdue invoice counts.")
    public record Summary(
            @Schema(description = "Transactions with no candidate match yet") long unmatchedTransactions,
            @Schema(description = "Transactions with a system-proposed match awaiting confirmation") long suggestedMatches,
            @Schema(description = "Transactions flagged for manual review") long needsReview,
            @Schema(description = "Overdue customer (accounts receivable) invoices") long overdueCustomers,
            @Schema(description = "Overdue supplier (accounts payable) invoices") long overdueSuppliers) {
    }
}

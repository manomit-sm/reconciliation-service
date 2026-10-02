package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * API 1 ("Get Reconciliation Summary") response. The request selects its period either by
 * {@code year}+{@code month} or - client feedback 2026-10-02, point 1, the dashboard now sends
 * a date range - by {@code startDate}+{@code endDate}; whichever pair was used is echoed back
 * and the other is omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Dashboard counters for the requested period (a calendar month, or an explicit date range).")
public record ReconciliationSummaryResponse(
        @Schema(description = "Calendar year. Present when the request used year/month.", example = "2026") Integer year,
        @Schema(description = "Calendar month (1-12). Present when the request used year/month.", example = "9") Integer month,
        @Schema(description = "Start of the requested range (inclusive). Present when the request used startDate/endDate.", example = "2026-09-25") LocalDate startDate,
        @Schema(description = "End of the requested range (inclusive). Present when the request used startDate/endDate.", example = "2026-10-02") LocalDate endDate,
        Summary summary) {

    /** The year/month form. */
    public ReconciliationSummaryResponse(Integer year, Integer month, Summary summary) {
        this(year, month, null, null, summary);
    }

    /** The startDate/endDate form. */
    public ReconciliationSummaryResponse(LocalDate startDate, LocalDate endDate, Summary summary) {
        this(null, null, startDate, endDate, summary);
    }

    @Schema(description = "Per-status transaction counts and overdue invoice counts.")
    public record Summary(
            @Schema(description = "Transactions with no candidate match yet") long unmatchedTransactions,
            @Schema(description = "Transactions with a system-proposed match awaiting confirmation") long suggestedMatches,
            @Schema(description = "Transactions flagged for manual review") long needsReview,
            @Schema(description = "Overdue customer (accounts receivable) invoices") long overdueCustomers,
            @Schema(description = "Overdue supplier (accounts payable) invoices") long overdueSuppliers) {
    }
}

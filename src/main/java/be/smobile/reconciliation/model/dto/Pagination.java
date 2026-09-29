package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shared pagination block for API 3 ("Get Bank Transactions") and API 5 ("Get Alternative
 * Invoice Matches"). The 2026-09-16 "Frontend API Requirements" doc's own two examples disagree
 * with each other on this field's casing (API 3: {@code total_pages}, API 5: {@code totalPages})
 * - deliberately standardized here on the snake_case spelling, since that's what the rest of
 * the doc uses pervasively for every other multi-word field
 * ({@code transaction_date}/{@code account_id}/{@code reconciliation_status}/etc.), and the
 * doc's own cover note says the backend can adjust implementation details like this as long as
 * the underlying data is there.
 */
@Schema(description = "Page metadata for a paginated list response.")
public record Pagination(
        @Schema(description = "Current 1-based page number", example = "1") int page,
        @Schema(description = "Page size", example = "20") int limit,
        @Schema(description = "Total number of matching items across all pages") long total,
        @Schema(description = "Total number of pages") @JsonProperty("total_pages") int totalPages) {

    public static Pagination of(int page, int limit, long total) {
        int totalPages = limit <= 0 ? 0 : (int) Math.ceil((double) total / limit);
        return new Pagination(page, limit, total, totalPages);
    }
}

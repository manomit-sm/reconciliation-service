package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** API 5 ("Get Alternative Invoice Matches") response envelope. */
@Schema(description = "Paginated list of alternative invoice-match candidates for a transaction.")
public record AlternativeMatchesResponse(
        @Schema(description = "Candidate invoices on this page") List<AlternativeInvoice> data,
        Pagination pagination) {
}

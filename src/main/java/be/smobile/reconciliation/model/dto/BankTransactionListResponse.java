package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** API 3 ("Get Bank Transactions") response envelope. */
@Schema(description = "Paginated list of bank transactions.")
public record BankTransactionListResponse(
        @Schema(description = "Transactions on this page") List<BankTransactionSummary> transactions,
        Pagination pagination) {
}

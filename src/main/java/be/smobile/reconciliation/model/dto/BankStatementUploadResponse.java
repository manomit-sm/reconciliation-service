package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** API 2 ("Upload Bank Statement") success response. */
@Schema(description = "Result of a bank statement upload.")
public record BankStatementUploadResponse(
        @Schema(description = "Whether the statement was imported successfully", example = "true") boolean success,
        @Schema(description = "Human-readable outcome message", example = "Bank statement uploaded successfully") String message) {

    public static BankStatementUploadResponse succeeded() {
        return new BankStatementUploadResponse(true, "Bank statement uploaded successfully");
    }
}

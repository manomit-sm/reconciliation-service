package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One row of API 5's alternative matches - {@link InvoiceSummary}'s fields plus the supplier
 * and due date the UI needs to tell similar-looking candidates apart (client feedback
 * 2026-09-28, point 1). Field names match that section's updated example verbatim, including
 * its deliberately mixed casing ({@code supplier_name} but {@code dueDate}).
 */
@Schema(description = "One candidate invoice for an alternative match, with its supplier and due date.")
public record AlternativeInvoice(
        @Schema(description = "Invoice id") Long id,
        @Schema(description = "Human-readable invoice number", example = "INV00002") @JsonProperty("invoice_number") String invoiceNumber,
        @Schema(description = "Total amount due on the invoice") Double amount,
        @Schema(description = "ISO 4217 currency code", example = "EUR") String currency,
        @Schema(description = "Whether this is a customer or supplier invoice", allowableValues = {"credit", "debit"}) String type,
        @Schema(description = "Invoice's own computed status", example = "overdue") String status,
        @Schema(description = "Name of the counterparty on the invoice - the supplier for an expense, the customer for a sales invoice", example = "Amazon Marketplace Europe")
        @JsonProperty("supplier_name") String supplierName,
        @Schema(description = "Invoice due date (UTC midnight of the due day). Absent when the invoice has no due date.", example = "2026-09-25T00:00:00Z")
        @JsonProperty("dueDate") Instant dueDate) {
}

package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * One invoice as shown in {@code matched_invoices} (API 4) or as a row of API 5's alternative
 * matches - same shape in both places in the doc's own examples.
 *
 * @param type   "credit"/"debit" - reuses the same vocabulary as {@code BankTransaction.direction}
 *               for whether this document is money owed <i>to</i> the company (customer invoice,
 *               "credit") or <i>by</i> it (supplier invoice, "debit") - see
 *               {@code ReconciliationDtoMapper#invoiceTypeWireValue}.
 * @param status the invoice's own computed status (e.g. "paid"), lowercased - see
 *               {@code InvoiceStatusCalculator}/{@code InvoiceStatusPresenter}, this service's
 *               already-existing status algorithm.
 */
@Schema(description = "One invoice, as shown in matched invoices or alternative-match candidates.")
public record InvoiceSummary(
        @Schema(description = "Invoice id") Long id,
        @Schema(description = "Human-readable invoice number", example = "INV-2026-001") @JsonProperty("invoice_number") String invoiceNumber,
        @Schema(description = "Total amount due on the invoice") Double amount,
        @Schema(description = "ISO 4217 currency code", example = "EUR") String currency,
        @Schema(description = "Whether this is a customer or supplier invoice", allowableValues = {"credit", "debit"}) String type,
        @Schema(description = "Invoice's own computed status", example = "paid") String status,
        @Schema(description = "Bank of the transaction this invoice was reconciled against. Present only in a reconciled transaction's matched_invoices.", example = "ING Belgium")
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("bank_name") String bankName,
        @Schema(description = "Free-text description of the invoice/expense") String description,
        @Schema(description = "Date the invoice was issued") @JsonProperty("invoice_date") LocalDate invoiceDate,
        @Schema(description = "The counterparty on the invoice - the supplier for an expense, the customer for a sales invoice", example = "NCR Voyix Belgium BV")
        @JsonProperty("supplier_name") String supplierName) {

    /** Same invoice, tagged with the bank of the transaction it was reconciled against - client feedback 2026-09-28 (point 3). */
    public InvoiceSummary withBankName(String bankName) {
        return new InvoiceSummary(id, invoiceNumber, amount, currency, type, status, bankName, description, invoiceDate, supplierName);
    }
}

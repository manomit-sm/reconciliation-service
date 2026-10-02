package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for {@code MultiPaymentAllocationService.PaymentGroup} - see that class's javadoc
 * for why this exists and isn't the client doc's own (inconsistently-edited)
 * {@code matched_transactions}/{@code payment_invoices} field names. Populated on
 * {@code BankTransactionDetail} only when this transaction is part of a detected group; {@code
 * null}/omitted otherwise.
 */
@Schema(description = "A suggested cross-payment/cross-invoice allocation: N unreconciled payments from the same partner covering M of that "
        + "partner's open invoices. Present only when such a group was detected for this transaction.")
public record MultiPaymentAllocation(
        @Schema(description = "Every payment in this group, in the order they were applied") List<PaymentSummary> payments,
        @Schema(description = "One row per (payment, invoice) pair the waterfall allocated") List<AllocationLine> allocations,
        @Schema(description = "Sum of every payment's amount") @JsonProperty("total_payments") BigDecimal totalPayments,
        @Schema(description = "Sum of every allocation line's amount - always equal to total_payments, see the owning service's javadoc")
        @JsonProperty("total_allocated") BigDecimal totalAllocated,
        @Schema(description = "total_payments minus total_allocated; always 0 for a returned group") BigDecimal difference) {

    @Schema(description = "One payment (bank transaction) in the group.")
    public record PaymentSummary(
            @Schema(description = "Bank transaction id") @JsonProperty("transaction_id") UUID transactionId,
            @Schema(description = "Display label distinguishing payments in this group", example = "A") String label,
            @Schema(description = "Date the payment occurred") @JsonProperty("transaction_date") LocalDate transactionDate,
            @Schema(description = "Payment amount") BigDecimal amount) {
    }

    @Schema(description = "One (payment, invoice) allocation line.")
    public record AllocationLine(
            @Schema(description = "Which payment's label this line belongs to", example = "A") String payment,
            @Schema(description = "Invoice id") @JsonProperty("invoice_id") Long invoiceId,
            @Schema(description = "Human-readable invoice number", example = "INV-2026-031") @JsonProperty("invoice_number") String invoiceNumber,
            @Schema(description = "The counterparty on the invoice - the supplier for an expense, the customer for a sales invoice", example = "Wholesale Co")
            @JsonProperty("supplier_name") String supplierName,
            @Schema(description = "Amount of this payment allocated to this invoice") BigDecimal amount) {
    }
}

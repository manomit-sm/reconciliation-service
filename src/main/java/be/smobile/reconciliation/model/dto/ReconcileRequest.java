package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * API 7 ("Approve Match / Reconcile Transaction") request body - covers both listed use cases
 * ("the user accepts the system-suggested match" and "the user manually selects one or more
 * alternative invoices") the same way: just the chosen invoice ids, no amounts. Per-invoice
 * allocation amounts are computed server-side against each invoice's current remaining balance -
 * see {@code ReconciliationService#reconcile}.
 * <p>
 * {@code expenseIds} ("If Expenses Are Also Supported") is accepted here so a caller sending it
 * gets a clear 400 rather than a silently-ignored field, but isn't implemented yet - the
 * "Employee expense flow" is Milestone 3's own separate, still-unspecified bullet.
 */
@Schema(description = "Invoices to reconcile a bank transaction against.")
public record ReconcileRequest(
        @Schema(description = "Invoice ids to reconcile against; required, must not be empty", example = "[1, 2]") List<Long> invoiceIds,
        @Schema(description = "Employee expense ids (not yet supported - results in a 501)") List<Long> expenseIds) {
}

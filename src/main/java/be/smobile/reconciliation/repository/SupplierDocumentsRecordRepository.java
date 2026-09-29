package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Read access to {@code supplier_documents_record} - owned by account-service, not this
 * service. Extends {@link JpaRepository} for the usual finder conveniences.
 * <p>
 * <b>Temporary exception, 2026-09-13:</b> the reconciliation module's "Create invoice" /
 * "Create expense" actions (Unmatched screen) need to insert new rows here even though this
 * table isn't owned by this service - the user explicitly asked to break the read-only
 * boundary for now, purely to be able to test end-to-end, with the insert code to be removed
 * once account-service exposes a real API for this. Every other use of this repository stays
 * read-only.
 */
public interface SupplierDocumentsRecordRepository extends JpaRepository<SupplierDocumentsRecord, Long> {

    /**
     * Candidate invoices for matching: due on/after {@code cutoff}, <b>or with no due date at
     * all</b> (client feedback 2026-09-28: a newly created expense often has none yet, and a
     * plain {@code due_date >= cutoff} silently excluded every such row - SQL comparisons with
     * NULL are never true - so it could never appear as a suggestion). A recency window, not a
     * confirmed "open/unpaid" flag - {@code SupplierDocumentsRecord} has no reliably-mapped
     * status column to filter on yet (see this entity's own javadoc), so "recent" is the only
     * defensible narrowing available; {@code remainingBalance <= 0} candidates (already fully
     * allocated) are filtered out afterwards by the caller once allocations are known, not here.
     */
    List<SupplierDocumentsRecord> findByDueDateGreaterThanEqualOrDueDateIsNull(LocalDate cutoff);

    /**
     * Candidates for the "Overdue Customers"/"Overdue Suppliers" dashboard counts (API 1 of the
     * 2026-09-16 "Frontend API Requirements" doc) - due strictly before {@code cutoff} (normally
     * today). Same caveat as {@link #findByDueDateGreaterThanEqualOrDueDateIsNull}: no confirmed
     * open/draft/cancelled flag to filter on here either, so every row past its due date comes
     * back and {@code ReconciliationDashboardService} does the rest (excluding drafts/cancelled
     * via {@code status}'s documented-but-unconfirmed code mapping, then the real PAID/OVERDUE
     * computation via {@link be.smobile.reconciliation.status.InvoiceStatusCalculator}).
     */
    List<SupplierDocumentsRecord> findByDueDateLessThan(LocalDate cutoff);
}

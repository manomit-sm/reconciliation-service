package be.smobile.reconciliation.status;

import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Implements the "Invoice Status Algorithm" from section 9 of the design doc:
 *
 * <pre>
 * if(balanceAmount == 0)
 *     status = PAID;
 * else if(paidAmount > 0)
 *     status = PARTIALLY_PAID;
 * else if(currentDate.isAfter(dueDate))
 *     status = OVERDUE;
 * else if(currentDate.equals(dueDate))
 *     status = DUE_TODAY;
 * else
 *     status = TO_BE_PAID;
 * </pre>
 * <p>
 * One deliberate extension on top of the literal pseudocode, required by section 2's business
 * requirements without changing the computation itself: {@code TO_BE_PAID} vs.
 * {@code TO_BE_RECEIVED} - the same "open, not yet due" state is labelled differently per
 * {@link InvoiceType} (supplier vs. customer), so {@code type} is a parameter.
 * <p>
 * Takes {@code paidAmount}/{@code balanceAmount} as parameters rather than reading them off an
 * invoice entity, and does <b>not</b> handle the {@code DRAFT}/{@code CANCELLED} pass-through
 * this class used to do when it worked against this module's own {@code Invoice} entity: the
 * shared entity this module now reuses instead ({@code SupplierDocumentsRecord}, per the
 * client's 2026-09-12 feedback) has no {@code paidAmount}/{@code balanceAmount} columns
 * (intended to be computed by the caller from this module's own {@code reconciliation.allocated_amount}
 * rows against {@code totalDue} - not yet wired up to a real query, pending confirmation), and
 * its {@code status} field's code-to-state mapping isn't fully confirmed (see
 * {@code SupplierDocumentsRecord}'s javadoc) - so callers are expected to only invoke this for
 * records that are already known to be open/issued and not cancelled, rather than this class
 * guessing at that from an unconfirmed status code.
 */
@Component
public class InvoiceStatusCalculator {

    /**
     * Computes the status an open (not draft, not cancelled) invoice should have as of
     * {@code currentDate}. Pure function - no persistence, no side effects.
     */
    public InvoiceStatus calculate(InvoiceType type, BigDecimal paidAmount, BigDecimal balanceAmount,
                                    LocalDate dueDate, LocalDate currentDate) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(paidAmount, "paidAmount");
        Objects.requireNonNull(balanceAmount, "balanceAmount");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(currentDate, "currentDate");

        if (balanceAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return InvoiceStatus.PAID;
        }
        if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            return InvoiceStatus.PARTIALLY_PAID;
        }
        if (currentDate.isAfter(dueDate)) {
            return InvoiceStatus.OVERDUE;
        }
        if (currentDate.isEqual(dueDate)) {
            return InvoiceStatus.DUE_TODAY;
        }
        return type == InvoiceType.CUSTOMER ? InvoiceStatus.TO_BE_RECEIVED : InvoiceStatus.TO_BE_PAID;
    }
}

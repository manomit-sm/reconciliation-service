package be.smobile.reconciliation.status;

import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies section 9's algorithm against the worked examples from section 2 of the design
 * doc (Today = 01/09/2026 / Due = 15/09/2026 -> TO_BE_PAID; Today = 18/09/2026 / Due =
 * 15/09/2026 -> OVERDUE 3d; etc.).
 * <p>
 * Exercises {@link InvoiceStatusCalculator} directly with plain values rather than an invoice
 * entity - see its javadoc for why (the shared {@code SupplierDocumentsRecord} entity this
 * module reuses has no paidAmount/balanceAmount columns to build a fixture from).
 */
class InvoiceStatusCalculatorTest {

    private final InvoiceStatusCalculator calculator = new InvoiceStatusCalculator();

    @Test
    void balanceZero_isPaid_regardlessOfDueDateOrType() {
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                new BigDecimal("250.00"),
                BigDecimal.ZERO,
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 1));

        assertThat(status).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void partialPayment_isPartiallyPaid_evenIfOverdue() {
        // design doc section 5.3, "Payment 1": allocated 400 of 1000 -> Partially Paid
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                new BigDecimal("400.00"),
                new BigDecimal("600.00"),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 18));

        assertThat(status).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    void unpaid_pastDueDate_isOverdue() {
        // design doc section 2: Today = 18/09/2026, Due Date = 15/09/2026 -> "Overdue 3d"
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 18));

        assertThat(status).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    void unpaid_dueDateIsToday_isDueToday() {
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 15));

        assertThat(status).isEqualTo(InvoiceStatus.DUE_TODAY);
    }

    @Test
    void unpaid_beforeDueDate_supplierInvoice_isToBePaid() {
        // design doc section 2: Today = 01/09/2026, Due Date = 15/09/2026 -> "To Be Paid 14d"
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 1));

        assertThat(status).isEqualTo(InvoiceStatus.TO_BE_PAID);
    }

    @Test
    void unpaid_beforeDueDate_customerInvoice_isToBeReceived() {
        InvoiceStatus status = calculator.calculate(
                InvoiceType.CUSTOMER,
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 1));

        assertThat(status).isEqualTo(InvoiceStatus.TO_BE_RECEIVED);
    }

    @Test
    void overpaid_balanceGoesNegative_isStillTreatedAsPaid() {
        InvoiceStatus status = calculator.calculate(
                InvoiceType.SUPPLIER,
                new BigDecimal("300.00"),
                new BigDecimal("-50.00"),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 1));

        assertThat(status).isEqualTo(InvoiceStatus.PAID);
    }
}

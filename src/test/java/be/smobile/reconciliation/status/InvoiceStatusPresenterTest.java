package be.smobile.reconciliation.status;

import be.smobile.reconciliation.constant.ReconciliationConstant;
import be.smobile.reconciliation.model.enums.BadgeColor;
import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matches the exact label/color pairs from section 2's "Status Computation" examples.
 */
class InvoiceStatusPresenterTest {

    private final InvoiceStatusPresenter presenter = new InvoiceStatusPresenter();

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 9, 15);

    @Test
    void toBePaid_showsDaysRemainingInGreen() {
        InvoiceStatusBadge badge = presenter.badge(
                InvoiceStatus.TO_BE_PAID, InvoiceType.SUPPLIER, DUE_DATE, LocalDate.of(2026, 9, 1));

        assertThat(badge).isEqualTo(new InvoiceStatusBadge(ReconciliationConstant.TO_BE_PAID + " 14d", BadgeColor.GREEN));
    }

    @Test
    void dueToday_showsDueTodayInOrange() {
        InvoiceStatusBadge badge = presenter.badge(
                InvoiceStatus.DUE_TODAY, InvoiceType.SUPPLIER, DUE_DATE, LocalDate.of(2026, 9, 15));

        assertThat(badge).isEqualTo(new InvoiceStatusBadge(ReconciliationConstant.DUE_TODAY, BadgeColor.ORANGE));
    }

    @Test
    void overdue_showsDaysLateInRed() {
        InvoiceStatusBadge badge = presenter.badge(
                InvoiceStatus.OVERDUE, InvoiceType.SUPPLIER, DUE_DATE, LocalDate.of(2026, 9, 18));

        assertThat(badge).isEqualTo(new InvoiceStatusBadge(ReconciliationConstant.OVERDUE + " 3d", BadgeColor.RED));
    }

    @Test
    void paid_showsPaidInGreen() {
        InvoiceStatusBadge badge = presenter.badge(
                InvoiceStatus.PAID, InvoiceType.SUPPLIER, DUE_DATE, LocalDate.of(2026, 9, 1));

        assertThat(badge).isEqualTo(new InvoiceStatusBadge(ReconciliationConstant.PAID, BadgeColor.GREEN));
    }

    @Test
    void toBeReceived_customerInvoice_showsDaysRemainingInGreen() {
        InvoiceStatusBadge badge = presenter.badge(
                InvoiceStatus.TO_BE_RECEIVED, InvoiceType.CUSTOMER, DUE_DATE, LocalDate.of(2026, 9, 1));

        assertThat(badge).isEqualTo(new InvoiceStatusBadge(ReconciliationConstant.TO_BE_RECEIVED + " 14d", BadgeColor.GREEN));
    }
}

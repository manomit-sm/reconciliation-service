package be.smobile.reconciliation.status;

import be.smobile.reconciliation.constant.ReconciliationConstant;
import be.smobile.reconciliation.model.enums.BadgeColor;
import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Turns an already-computed {@link InvoiceStatus} into the label/color pairs shown in section
 * 2's examples, e.g. "To Be Paid 14d" (Green), "Due Today" (Orange), "Overdue 3d" (Red), "Paid"
 * (Green). Kept separate from {@link InvoiceStatusCalculator}: the calculator is the one piece
 * of business logic that decides {@code status}; this class only formats it.
 * <p>
 * Takes {@code status}/{@code type}/{@code dueDate} as parameters rather than an invoice
 * entity, for the same reason as {@link InvoiceStatusCalculator} - see its javadoc.
 */
@Component
public class InvoiceStatusPresenter {

    public InvoiceStatusBadge badge(InvoiceStatus status, InvoiceType type, LocalDate dueDate, LocalDate currentDate) {
        return switch (status) {
            case PAID -> new InvoiceStatusBadge(ReconciliationConstant.PAID, BadgeColor.GREEN);
            case PARTIALLY_PAID -> new InvoiceStatusBadge(ReconciliationConstant.PARTIALLY_PAID, BadgeColor.ORANGE);
            case DUE_TODAY -> new InvoiceStatusBadge(ReconciliationConstant.DUE_TODAY, BadgeColor.ORANGE);
            case OVERDUE -> new InvoiceStatusBadge(
                    ReconciliationConstant.OVERDUE + " " + ChronoUnit.DAYS.between(dueDate, currentDate) + "d", BadgeColor.RED);
            case TO_BE_PAID, TO_BE_RECEIVED -> {
                String label = type == InvoiceType.CUSTOMER ? ReconciliationConstant.TO_BE_RECEIVED : ReconciliationConstant.TO_BE_PAID;
                yield new InvoiceStatusBadge(
                        label + " " + ChronoUnit.DAYS.between(currentDate, dueDate) + "d",
                        BadgeColor.GREEN);
            }
            case DRAFT -> new InvoiceStatusBadge(ReconciliationConstant.DRAFT, BadgeColor.GRAY);
            case APPROVED -> new InvoiceStatusBadge(ReconciliationConstant.APPROVED, BadgeColor.GRAY);
            case SENT -> new InvoiceStatusBadge(ReconciliationConstant.SENT, BadgeColor.GRAY);
            case CANCELLED -> new InvoiceStatusBadge(ReconciliationConstant.CANCELLED, BadgeColor.GRAY);
        };
    }
}

package be.smobile.reconciliation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Optional extra {@code status} codes that never count as "overdue" in the dashboard summary's
 * Overdue Customers / Overdue Suppliers cards. <b>Empty by default</b>: since the client's
 * 2026-10-08 feedback a card counts what the Invoices / Expenses lists show with a red "To Be
 * Paid" badge - any unpaid, non-draft document past its due date, whatever its {@code status}
 * code (the earlier defaults of 0/1/2/8 for customers hid red-badged invoices and made the card
 * read 16 where the list showed more). A document already marked paid in the document service
 * (status 6 / paid_status 1) is always excluded.
 * <p>
 * Kept as configuration, not removed, so a status the client later says is not "To Be Paid" can be
 * excluded without a release, e.g. {@code APP_SUMMARY_OVERDUE_EXCLUDED_CUSTOMER_STATUSES=8}.
 */
@ConfigurationProperties(prefix = "app.summary")
@Getter
@Setter
public class SummaryProperties {

    /** Sales-invoice statuses that don't count as overdue. */
    private List<Integer> overdueExcludedCustomerStatuses = List.of();

    /** Expense statuses that don't count as overdue. */
    private List<Integer> overdueExcludedSupplierStatuses = List.of();
}

package be.smobile.reconciliation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Which document {@code status} codes never count as "overdue" in the dashboard summary's
 * Overdue Customers / Overdue Suppliers cards (client feedback 2026-10-07, points 2 and 3).
 * <p>
 * {@code supplier_documents_record.status} is owned by the document service and its code
 * meanings are only partly documented ("0/8 = Draft, 1/2 = Unsent, 3/4 = Sent, 5 = Overdue,
 * 6 = Paid" - see {@code SupplierDocumentsRecord}); what the client's own screens show is that
 * the codes mean different things for the two kinds of document: a <i>sales invoice</i> that is
 * still "To be Sent" is not yet something the customer owes, but a freshly created
 * <i>expense</i> carries status 0 and is shown as "To Be Paid". So the defaults differ by side
 * and are configuration, not code, so they can be corrected without a release once the client
 * confirms the real codes (e.g. {@code APP_SUMMARY_OVERDUE_EXCLUDED_CUSTOMER_STATUSES=0,1,2,8}).
 * A document already marked paid in the document service (status 6 / paid_status 1) is always
 * excluded, whatever is listed here.
 */
@ConfigurationProperties(prefix = "app.summary")
@Getter
@Setter
public class SummaryProperties {

    /** Sales-invoice statuses that don't count as overdue: draft (0, 8) and not yet sent (1, 2). */
    private List<Integer> overdueExcludedCustomerStatuses = List.of(0, 1, 2, 8);

    /** Expense statuses that don't count as overdue: only the documented draft code (8) - status 0 is a normal, unpaid expense. */
    private List<Integer> overdueExcludedSupplierStatuses = List.of(8);
}

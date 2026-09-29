package be.smobile.reconciliation.repository;

import java.math.BigDecimal;

/** Projection for {@link ReconciliationRepository#sumAllocatedAmountsByInvoiceIds}. */
public interface InvoiceAllocatedTotal {
    Long getInvoiceId();

    BigDecimal getTotal();
}

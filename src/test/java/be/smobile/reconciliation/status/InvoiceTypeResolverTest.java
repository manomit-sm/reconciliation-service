package be.smobile.reconciliation.status;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.model.enums.InvoiceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceTypeResolverTest {

    private final InvoiceTypeResolver resolver = new InvoiceTypeResolver();

    @Test
    void supplierIdPresent_isSupplier() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder().id(1L).supplierId(9L).build();
        assertEquals(InvoiceType.SUPPLIER, resolver.resolve(record));
    }

    @Test
    void supplierIdAbsent_isCustomer() {
        SupplierDocumentsRecord record = SupplierDocumentsRecord.builder().id(1L).build();
        assertEquals(InvoiceType.CUSTOMER, resolver.resolve(record));
    }
}

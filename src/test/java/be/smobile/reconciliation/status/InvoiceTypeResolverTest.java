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

    /** The client's new expenses have no supplierId (record 72, an EXPENSE) but crdr "debit" - they are supplier documents. */
    @Test
    void crdrDebit_isSupplier_evenWithoutASupplierId() {
        assertEquals(InvoiceType.SUPPLIER, resolver.resolve(SupplierDocumentsRecord.builder().id(1L).crdr("debit").build()));
        assertEquals(InvoiceType.SUPPLIER, resolver.resolve(SupplierDocumentsRecord.builder().id(1L).crdr(" Debit ").build()));
    }

    @Test
    void crdrCredit_isCustomer_evenWithASupplierId() {
        assertEquals(InvoiceType.CUSTOMER, resolver.resolve(SupplierDocumentsRecord.builder().id(1L).crdr("credit").supplierId(9L).build()));
    }

    @Test
    void otherCrdrValues_fallBackToTheSupplierIdGuess() {
        assertEquals(InvoiceType.SUPPLIER, resolver.resolve(SupplierDocumentsRecord.builder().id(1L).crdr("debit_note").supplierId(9L).build()));
        assertEquals(InvoiceType.CUSTOMER, resolver.resolve(SupplierDocumentsRecord.builder().id(1L).crdr("debit_note").build()));
    }
}

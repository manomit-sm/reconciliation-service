package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InvoiceNumbersTest {

    @Test
    void realInvoiceNumberWins() {
        assertEquals("INV-1", InvoiceNumbers.of(SupplierDocumentsRecord.builder().invoiceNumber("INV-1").reference("EX1").build()));
    }

    /** Imported expenses in the client's test data: invoice_number "" and the number in reference. */
    @Test
    void blankInvoiceNumber_fallsBackToReference() {
        assertEquals("EX2026092813011110", InvoiceNumbers.of(SupplierDocumentsRecord.builder().invoiceNumber("").reference("EX2026092813011110").build()));
        assertEquals("EX1", InvoiceNumbers.of(SupplierDocumentsRecord.builder().invoiceNumber(null).reference("EX1").build()));
    }

    @Test
    void neitherPresent_isNull() {
        assertNull(InvoiceNumbers.of(SupplierDocumentsRecord.builder().invoiceNumber(" ").reference("").build()));
    }
}

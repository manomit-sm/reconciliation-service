package be.smobile.reconciliation.imports;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BankAccountIdParserTest {

    @Test
    void uuid_isUsedAsIs() {
        UUID id = UUID.randomUUID();
        assertEquals(id, BankAccountIdParser.parse(id.toString()));
        assertEquals(id, BankAccountIdParser.parse("  " + id + " "));
    }

    /** Client bug report 2026-09-28 (point 4): the frontend sends the numeric account id. */
    @Test
    void numericId_isAcceptedAndMapsToTheSameUuidEveryTime() {
        UUID first = BankAccountIdParser.parse("9000000447");
        assertEquals(first, BankAccountIdParser.parse("9000000447"));
        assertEquals(first, BankAccountIdParser.parse(" 9000000447 "));
        assertNotEquals(first, BankAccountIdParser.parse("9000000448"));
    }

    @Test
    void anythingElse_isABadRequestNotAServerError() {
        assertThrows(IllegalArgumentException.class, () -> BankAccountIdParser.parse("not-an-id"));
        assertThrows(IllegalArgumentException.class, () -> BankAccountIdParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> BankAccountIdParser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> BankAccountIdParser.parse("9000000447x"));
    }
}

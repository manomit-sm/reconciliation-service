package be.smobile.reconciliation.imports;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Turns the {@code accountId} a caller passes to "Upload Bank Statement" into the {@link UUID}
 * this service stores. Client bug report 2026-09-28 (point 4): the frontend sends the company's
 * numeric account id ({@code 9000000447}), not a UUID, and a {@code UUID}-typed request
 * parameter rejected it with "Failed to convert 'accountId' with value: '9000000447'".
 * <p>
 * A real UUID is used as-is. A numeric id (the only other form the frontend has) can't be one,
 * but {@code bank_transaction.account_id}/{@code bank_statement.account_id} are UUID columns
 * (client-supplied schema), so it is mapped to a <b>name-based UUID</b> - the same input always
 * yields the same UUID, which is what matters: every statement uploaded for one account still
 * lands under one {@code account_id}, so duplicate-range detection and multi-payment grouping
 * (both keyed on it) keep working. Not reversible, by design.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BankAccountIdParser {

    private static final String NAME_PREFIX = "pilim-bank-account:";

    /** @throws IllegalArgumentException (-&gt; HTTP 400) if {@code value} is neither a UUID nor a plain number */
    public static UUID parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{1,19}")) {
            return UUID.nameUUIDFromBytes((NAME_PREFIX + trimmed).getBytes(StandardCharsets.UTF_8));
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("accountId must be a UUID or a numeric account id, was '" + value + "'");
        }
    }
}

package be.smobile.reconciliation.model.enums;

import be.smobile.reconciliation.entity.BankTransaction;

import java.util.Locale;

/**
 * Sign of a {@link BankTransaction}'s amount, e.g. section 4: "Customer ABC +1500 €" (CREDIT,
 * money coming in) vs. "Microsoft -250 €" (DEBIT, money going out).
 * <p>
 * Wire value for the frontend's {@code direction} field is this constant's name lowercased
 * ({@code credit}/{@code debit}) - see {@link #wireValue()} - matching the "Frontend API
 * Requirements" doc's own examples; DB storage (a plain {@code VARCHAR(16)} via
 * {@code @Enumerated(EnumType.STRING)}) is unaffected, still the uppercase name.
 */
public enum TransactionDirection {
    CREDIT,
    DEBIT;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.model.enums.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction line within a {@link BankStatementExtraction}. {@code amount} is always a
 * positive magnitude - sign/direction is carried separately by {@code direction}, matching how
 * {@code BankTransaction} itself already models it, rather than a signed amount the AI service
 * would have to get the sign convention of exactly right.
 */
public record ExtractedTransaction(LocalDate transactionDate, String description, String reference, BigDecimal amount, TransactionDirection direction) {
}

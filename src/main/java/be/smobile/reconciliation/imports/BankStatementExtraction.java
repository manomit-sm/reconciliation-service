package be.smobile.reconciliation.imports;

import java.util.List;

/**
 * The JSON contract this service expects back from Pilim's AI extraction microservice for one
 * bank/credit-card statement PDF - the "transactions" analog of the existing invoice-extraction
 * contract (client-supplied 2026-09-13), which this service does not own; it's a <b>proposal
 * to hand back to the client</b> so their AI microservice team can implement a matching
 * endpoint, per their own request ("You can give me the json you expect then we will update
 * our AI microservice").
 * <p>
 * Field choices, explained:
 * <ul>
 *     <li>{@code bankName} - printed on the statement, read directly, no lookup/matching
 *     needed - stored as-is on {@code BankTransaction.bankName} per the client's request.</li>
 *     <li>{@code accountNumber} - the IBAN/account number as printed. <b>Not currently used
 *     to resolve {@code BankTransaction.accountId}</b> (Pilim's own internal bank-account
 *     UUID) - this service has no table of Pilim's registered bank accounts to match it
 *     against (unlike {@code SupplierDocumentsRecord}, no such entity exists here yet). Until
 *     that table's location/shape is confirmed, {@code BankTransactionImportService} takes the
 *     target {@code accountId} as an explicit caller-supplied parameter instead of trying to
 *     resolve it from this field.</li>
 *     <li>{@code direction} matches {@code TransactionDirection} (CREDIT/DEBIT) directly -
 *     mirrors the existing enum rather than inventing separate wire vocabulary.</li>
 * </ul>
 */
public record BankStatementExtraction(String bankName, String accountNumber, String currency, List<ExtractedTransaction> transactions) {
}

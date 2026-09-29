package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.entity.BankTransaction;

import java.util.List;

/**
 * Outcome of one {@link BankTransactionImportService#importStatement} call.
 * <ul>
 *     <li>{@code duplicateFile}: this exact file (by MD5) was already imported - the client's
 *     answer was to reject it outright, so {@code imported}/{@code flaggedForReview} are both
 *     empty in that case.</li>
 *     <li>{@code flaggedForReview}: transactions whose date overlapped an already-imported
 *     range for this bank account - per the client's answer, these are neither auto-imported
 *     nor auto-rejected; a person needs to accept/reject them one by one. <b>Not persisted</b>
 *     by this service - building that review workflow (a UI, an accept/reject action) is
 *     Milestone 3 ("REST APIs... actions"), not this milestone's import-handling logic.</li>
 * </ul>
 */
public record ImportResult(List<BankTransaction> imported, List<ExtractedTransaction> flaggedForReview, boolean duplicateFile) {

    public static ImportResult forDuplicateFile() {
        return new ImportResult(List.of(), List.of(), true);
    }
}

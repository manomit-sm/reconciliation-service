package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.entity.BankStatement;
import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.SourceType;
import be.smobile.reconciliation.repository.BankStatementRepository;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Milestone 2 bullet 4 ("Bank transaction import handling"), per the client's 2026-09-13
 * clarification: statements are PDFs, parsed by Pilim's existing AI microservice (the same
 * pattern already used for invoice extraction) into a {@link BankStatementExtraction} - this
 * service is deliberately format-agnostic (no CODA/CAMT.053/MT940 parsing here at all), since
 * the client confirmed each bank's own statement format is handled upstream by that AI service,
 * not per-bank parsing code in this one. Only handles the PDF path - {@code PONTO}/{@code
 * MANUAL} (see {@link SourceType}) have no concrete ingestion requirements yet.
 * <p>
 * Duplicate handling matches the client's two-tier answer exactly:
 * <ol>
 *     <li>the exact same file re-uploaded (by MD5) is rejected outright - see
 *     {@link ImportResult#forDuplicateFile()}. Checked against {@link BankStatement} (moved
 *     there 2026-09-15, one row per imported file - see that entity's javadoc), not
 *     {@link BankTransaction} directly;</li>
 *     <li>a transaction whose date falls within an already-imported range for the same bank
 *     account is neither silently re-imported nor silently dropped - it's flagged for a human
 *     to accept/reject, per "the UI should show the user that there are overlap date ranges and
 *     the user accept or refuse overlapped range one by one." Building that accept/reject
 *     UI/action is Milestone 3 scope - see {@link ImportResult}'s javadoc.</li>
 * </ol>
 * Every cleanly-imported transaction is classified immediately via {@link ReconciliationService}
 * (the already-built engine core + rule-based matching engine, bullets 5/6) rather than landing
 * with some default status - so an import actually benefits from the engine that exists,
 * instead of leaving every new row unclassified until first viewed.
 */
@Service
@RequiredArgsConstructor
public class BankTransactionImportService {

    private final BankStatementRepository bankStatementRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final ReconciliationService reconciliationService;

    /**
     * @param accountId          Pilim's own internal bank-account id this statement is for -
     *                           supplied by the caller, not resolved from
     *                           {@link BankStatementExtraction#accountNumber()} (see that
     *                           record's javadoc for why)
     * @param fileMd5             MD5 of the original uploaded file, for exact-duplicate rejection
     * @param transactionFilePath wherever the file has been/will be stored (S3 path/key) -
     *                             stored as-is, not validated or uploaded by this service
     * @param extraction           the AI microservice's parsed result
     */
    @Transactional
    public ImportResult importStatement(UUID accountId, String fileMd5, String transactionFilePath, BankStatementExtraction extraction) {
        if (bankStatementRepository.existsByFileMd5(fileMd5)) {
            return ImportResult.forDuplicateFile();
        }
        if (extraction.transactions().isEmpty()) {
            return new ImportResult(List.of(), List.of(), false);
        }

        Instant now = Instant.now();
        BankStatement statement = bankStatementRepository.save(BankStatement.builder()
                .accountId(accountId)
                .bankName(extraction.bankName())
                .transactionFile(transactionFilePath)
                .fileMd5(fileMd5)
                .createdAt(now)
                .build());

        Set<LocalDate> alreadyImportedDates = alreadyImportedDatesInRange(accountId, extraction.transactions());

        List<BankTransaction> imported = new ArrayList<>();
        List<ExtractedTransaction> flaggedForReview = new ArrayList<>();

        for (ExtractedTransaction extracted : extraction.transactions()) {
            if (alreadyImportedDates.contains(extracted.transactionDate())) {
                flaggedForReview.add(extracted);
                continue;
            }
            imported.add(persist(accountId, statement, extracted, now));
        }

        return new ImportResult(imported, flaggedForReview, false);
    }

    private Set<LocalDate> alreadyImportedDatesInRange(UUID accountId, List<ExtractedTransaction> transactions) {
        LocalDate minDate = transactions.stream().map(ExtractedTransaction::transactionDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate maxDate = transactions.stream().map(ExtractedTransaction::transactionDate).max(Comparator.naturalOrder()).orElseThrow();
        return new HashSet<>(bankTransactionRepository.findByAccountIdAndTransactionDateBetween(accountId, minDate, maxDate)
                .stream().map(BankTransaction::getTransactionDate).toList());
    }

    private BankTransaction persist(UUID accountId, BankStatement statement, ExtractedTransaction extracted, Instant now) {
        BankTransaction transaction = BankTransaction.builder()
                .accountId(accountId)
                .transactionDate(extracted.transactionDate())
                .description(extracted.description())
                .reference(extracted.reference())
                .amount(extracted.amount())
                .direction(extracted.direction())
                .reconciliationStatus(BankTransactionStatus.UNMATCHED)
                .bankName(statement.getBankName())
                .statement(statement)
                .sourceType(SourceType.PDF)
                .createdAt(now)
                .updatedAt(now)
                .build();

        MatchSuggestion suggestion = reconciliationService.suggest(transaction);
        BankTransactionStatus status = reconciliationService.classify(suggestion);
        transaction.setReconciliationStatus(status);

        BankTransaction saved = bankTransactionRepository.save(transaction);
        // 2026-09-19 client rule: a >= app.matching.auto-reconcile-threshold match reconciles
        // itself on import instead of landing as a suggested_match awaiting manual approval -
        // see ReconciliationService#classify/#autoConfirm.
        if (status == BankTransactionStatus.RECONCILED) {
            reconciliationService.autoConfirm(saved.getId(), suggestion.allocations());
        }
        return saved;
    }
}

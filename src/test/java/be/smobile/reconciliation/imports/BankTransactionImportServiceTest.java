package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.entity.BankStatement;
import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.SourceType;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.BankStatementRepository;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.service.ReconciliationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pins down the two-tier duplicate handling exactly as the client described it - see class javadoc on {@link BankTransactionImportService}. */
@ExtendWith(MockitoExtension.class)
class BankTransactionImportServiceTest {

    @Mock
    private BankStatementRepository bankStatementRepository;
    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private ReconciliationService reconciliationService;

    private BankTransactionImportService service() {
        return new BankTransactionImportService(bankStatementRepository, bankTransactionRepository, reconciliationService);
    }

    @Test
    void sameFileReuploaded_isRejectedOutright() {
        when(bankStatementRepository.existsByFileMd5("abc123")).thenReturn(true);

        ImportResult result = service().importStatement(UUID.randomUUID(), "abc123", "s3://bucket/file.pdf",
                new BankStatementExtraction("Belfius", "BE00", "EUR", List.of(extractedTransaction(LocalDate.of(2026, 9, 5)))));

        assertTrue(result.duplicateFile());
        assertTrue(result.imported().isEmpty());
        verify(bankStatementRepository, never()).save(any());
        verify(bankTransactionRepository, never()).save(any());
    }

    @Test
    void cleanImport_persistsEveryTransaction_classifiedByTheEngine() {
        when(bankStatementRepository.existsByFileMd5("md5")).thenReturn(false);
        when(bankStatementRepository.save(any())).thenAnswer(invocation -> {
            BankStatement statement = invocation.getArgument(0);
            statement.setId(UUID.randomUUID());
            return statement;
        });
        when(bankTransactionRepository.findByAccountIdAndTransactionDateBetween(any(), any(), any())).thenReturn(List.of());
        when(bankTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reconciliationService.suggest(any())).thenReturn(new MatchSuggestion(90, List.of(), List.of()));
        when(reconciliationService.classify(any())).thenReturn(BankTransactionStatus.SUGGESTED_MATCH);

        UUID accountId = UUID.randomUUID();
        BankStatementExtraction extraction = new BankStatementExtraction("Belfius", "BE00", "EUR",
                List.of(extractedTransaction(LocalDate.of(2026, 9, 5)), extractedTransaction(LocalDate.of(2026, 9, 6))));

        ImportResult result = service().importStatement(accountId, "md5", "s3://bucket/file.pdf", extraction);

        assertEquals(2, result.imported().size());
        assertTrue(result.flaggedForReview().isEmpty());
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, result.imported().getFirst().getReconciliationStatus());
        assertEquals("Belfius", result.imported().getFirst().getBankName());
        assertEquals(SourceType.PDF, result.imported().getFirst().getSourceType());
        assertNotNull(result.imported().getFirst().getStatement());
        assertEquals(accountId, result.imported().getFirst().getAccountId());

        verify(bankStatementRepository).save(ArgumentMatchers.argThat(s ->
                "Belfius".equals(s.getBankName())
                        && "s3://bucket/file.pdf".equals(s.getTransactionFile())
                        && "md5".equals(s.getFileMd5())
                        && accountId.equals(s.getAccountId())));
        verify(reconciliationService, never()).autoConfirm(any(), any());
    }

    /** 2026-09-19 client rule: "When the score is 98% or more... this transaction will not be reconciled manual" - see {@link ReconciliationService#classify}/{@link ReconciliationService#autoConfirm}. */
    @Test
    void cleanImport_engineClassifiesAsReconciled_autoConfirmsWithoutManualApproval() {
        when(bankStatementRepository.existsByFileMd5("md5")).thenReturn(false);
        when(bankStatementRepository.save(any())).thenAnswer(invocation -> {
            BankStatement statement = invocation.getArgument(0);
            statement.setId(UUID.randomUUID());
            return statement;
        });
        when(bankTransactionRepository.findByAccountIdAndTransactionDateBetween(any(), any(), any())).thenReturn(List.of());
        when(bankTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        List<InvoiceAllocation> allocations = List.of(new InvoiceAllocation(1L, new BigDecimal("250.00")));
        when(reconciliationService.suggest(any())).thenReturn(new MatchSuggestion(100, allocations, List.of()));
        when(reconciliationService.classify(any())).thenReturn(BankTransactionStatus.RECONCILED);

        UUID accountId = UUID.randomUUID();
        BankStatementExtraction extraction = new BankStatementExtraction("Belfius", "BE00", "EUR",
                List.of(extractedTransaction(LocalDate.of(2026, 9, 5))));

        ImportResult result = service().importStatement(accountId, "md5", "s3://bucket/file.pdf", extraction);

        assertEquals(BankTransactionStatus.RECONCILED, result.imported().getFirst().getReconciliationStatus());
        UUID importedId = result.imported().getFirst().getId();
        verify(reconciliationService).autoConfirm(importedId, allocations);
    }

    @Test
    void transactionOnAlreadyImportedDate_isFlaggedNotImported_othersStillImportCleanly() {
        UUID accountId = UUID.randomUUID();
        LocalDate overlappingDate = LocalDate.of(2026, 9, 5);
        LocalDate cleanDate = LocalDate.of(2026, 9, 6);
        BankTransaction existing = BankTransaction.builder().transactionDate(overlappingDate).build();

        when(bankStatementRepository.existsByFileMd5("md5")).thenReturn(false);
        when(bankStatementRepository.save(any())).thenAnswer(invocation -> {
            BankStatement statement = invocation.getArgument(0);
            statement.setId(UUID.randomUUID());
            return statement;
        });
        when(bankTransactionRepository.findByAccountIdAndTransactionDateBetween(any(), any(), any())).thenReturn(List.of(existing));
        when(bankTransactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reconciliationService.suggest(any())).thenReturn(MatchSuggestion.none());
        when(reconciliationService.classify(any())).thenReturn(BankTransactionStatus.UNMATCHED);

        BankStatementExtraction extraction = new BankStatementExtraction("Belfius", "BE00", "EUR",
                List.of(extractedTransaction(overlappingDate), extractedTransaction(cleanDate)));

        ImportResult result = service().importStatement(accountId, "md5", "s3://bucket/file.pdf", extraction);

        assertEquals(1, result.imported().size());
        assertEquals(cleanDate, result.imported().getFirst().getTransactionDate());
        assertEquals(1, result.flaggedForReview().size());
        assertEquals(overlappingDate, result.flaggedForReview().getFirst().transactionDate());
    }

    @Test
    void noNewFileHashCheck_whenTransactionsListIsEmpty_returnsEmptyResult() {
        when(bankStatementRepository.existsByFileMd5(anyString())).thenReturn(false);

        ImportResult result = service().importStatement(UUID.randomUUID(), "md5", "path", new BankStatementExtraction("Belfius", "BE00", "EUR", List.of()));

        assertTrue(result.imported().isEmpty());
        assertTrue(result.flaggedForReview().isEmpty());
        assertFalse(result.duplicateFile());
        verify(bankStatementRepository, never()).save(any());
    }

    private ExtractedTransaction extractedTransaction(LocalDate date) {
        return new ExtractedTransaction(date, "Customer ABC", "INV-001", new BigDecimal("250.00"), TransactionDirection.CREDIT);
    }
}

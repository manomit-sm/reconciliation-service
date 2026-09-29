package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.Reconciliation;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.matching.MatchCriterion;
import be.smobile.reconciliation.matching.MatchCriterionType;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.matching.MatchingCriteriaPresenter;
import be.smobile.reconciliation.matching.MatchingEngine;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.dto.AlternativeInvoice;
import be.smobile.reconciliation.model.dto.BankTransactionDetail;
import be.smobile.reconciliation.model.dto.BankTransactionSummary;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.SourceType;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceStatusCalculator;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankTransactionDtoMapperTest {

    @Mock
    private SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    @Mock
    private ReconciliationRepository reconciliationRepository;
    @Mock
    private ReconciliationCandidateResolver candidateResolver;
    @Mock
    private MatchingEngine matchingEngine;
    @Mock
    private MultiPaymentAllocationService multiPaymentAllocationService;

    private final MatchingProperties matchingProperties = new MatchingProperties();

    private BankTransactionDtoMapper mapper() {
        return new BankTransactionDtoMapper(supplierDocumentsRecordRepository, reconciliationRepository, candidateResolver,
                matchingEngine, matchingProperties, new MatchingCriteriaPresenter(), new InvoiceStatusCalculator(), new InvoiceTypeResolver(),
                multiPaymentAllocationService);
    }

    @Test
    void toSummary_mapsEveryFieldAndLowercasesWireEnumValues() {
        BankTransaction tx = transaction(BankTransactionStatus.UNMATCHED);

        BankTransactionSummary summary = mapper().toSummary(tx);

        assertEquals(tx.getId(), summary.id());
        assertEquals(tx.getAccountId(), summary.accountId());
        assertEquals("pdf", summary.sourceType());
        assertEquals("credit", summary.direction());
        assertEquals("unmatched", summary.reconciliationStatus());
        assertEquals(tx.getBankName(), summary.bankName());
        assertEquals(tx.getAmount(), summary.amount());
    }

    @Test
    void toDetail_unmatched_carriesOnlyTheBaseFields() {
        BankTransactionDetail detail = mapper().toDetail(transaction(BankTransactionStatus.UNMATCHED));

        assertEquals("unmatched", detail.reconciliationStatus());
        assertNull(detail.matchedInvoices());
        assertNull(detail.matchingCriteria());
        assertNull(detail.reviewedBy());
    }

    @Test
    void toDetail_reconciled_returnsMatchedInvoicesAndTheLatestReconciliationDate() {
        BankTransaction tx = transaction(BankTransactionStatus.RECONCILED);
        tx.setReviewerComment("looked fine");

        LocalDateTime earlier = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime later = LocalDateTime.of(2026, 9, 2, 10, 0);
        Reconciliation row1 = Reconciliation.builder().invoiceIds(List.of(1L)).allocatedAmounts(List.of(BigDecimal.TEN)).reconciliationDate(earlier)
                .createdBy("sub-old").createdByName("Older Person").build();
        Reconciliation row2 = Reconciliation.builder().invoiceIds(List.of(2L)).allocatedAmounts(List.of(BigDecimal.ONE)).reconciliationDate(later)
                .createdBy("sub-1").createdByName("Prince Boghara").build();
        when(reconciliationRepository.findByBankTransactionId(tx.getId())).thenReturn(List.of(row1, row2));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(1L, 2L))).thenReturn(List.of());
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).invoiceNumber("INV-001").totalDue(10.0).currency("EUR").dueDate(LocalDate.of(2020, 1, 1)).build(),
                SupplierDocumentsRecord.builder().id(2L).invoiceNumber("INV-002").totalDue(1.0).currency("EUR").dueDate(LocalDate.of(2020, 1, 1)).build()));

        BankTransactionDetail detail = mapper().toDetail(tx);

        assertEquals("reconciled", detail.reconciliationStatus());
        assertEquals("Prince Boghara", detail.reviewedBy());
        assertEquals("looked fine", detail.reviewerComment());
        assertEquals(later.atZone(ZoneId.systemDefault()).toInstant(), detail.reconciliationDate());
        assertEquals(2, detail.matchedInvoices().size());
        assertEquals("INV-001", detail.matchedInvoices().get(0).invoiceNumber());
        assertEquals("Belfius", detail.matchedInvoices().get(0).bankName());
    }

    @Test
    void toDetail_needsReview_carriesReviewerFieldsButNoMatchedInvoices() {
        BankTransaction tx = transaction(BankTransactionStatus.NEEDS_REVIEW);
        tx.setReviewerId("emp-2");
        tx.setReviewerName("Prince Boghara");
        tx.setReviewerComment("Wrong amount");
        Instant reviewedAt = Instant.parse("2026-09-28T07:17:43Z");
        tx.setReviewedAt(reviewedAt);

        BankTransactionDetail detail = mapper().toDetail(tx);

        assertEquals("needs_review", detail.reconciliationStatus());
        assertEquals("Prince Boghara", detail.reviewedBy());
        assertEquals("Wrong amount", detail.reviewerComment());
        assertNull(detail.matchedInvoices());
        assertEquals(reviewedAt, detail.reconciliationDate());
    }

    /** Rows written before reviewer names were captured only have the id - show that rather than nothing. */
    @Test
    void toDetail_needsReview_withoutAStoredName_fallsBackToTheReviewerId() {
        BankTransaction tx = transaction(BankTransactionStatus.NEEDS_REVIEW);
        tx.setReviewerId("emp-2");

        assertEquals("emp-2", mapper().toDetail(tx).reviewedBy());
    }

    @Test
    void toSummaries_needsReview_carriesFullNameCommentAndDate() {
        BankTransaction tx = transaction(BankTransactionStatus.NEEDS_REVIEW);
        tx.setReviewerId("emp-2");
        tx.setReviewerName("Prince Boghara");
        tx.setReviewerComment("Wrong amount");
        Instant reviewedAt = Instant.parse("2026-09-28T07:17:43Z");
        tx.setReviewedAt(reviewedAt);

        BankTransactionSummary summary = mapper().toSummaries(List.of(tx)).get(0);

        assertEquals("Prince Boghara", summary.reviewedBy());
        assertEquals("Wrong amount", summary.reviewerComment());
        assertEquals(reviewedAt, summary.reconciliationDate());
        assertNull(summary.matchedInvoices());
    }

    @Test
    void toSummaries_reconciled_carriesReviewerDateAndMatchedInvoicesWithBankName() {
        BankTransaction tx = transaction(BankTransactionStatus.RECONCILED);
        LocalDateTime when = LocalDateTime.of(2026, 9, 25, 20, 58, 30);
        Reconciliation row = Reconciliation.builder().bankTransaction(tx).invoiceIds(List.of(1L)).allocatedAmounts(List.of(BigDecimal.TEN))
                .reconciliationDate(when).createdBy("sub-1").createdByName("Prince Boghara").build();
        when(reconciliationRepository.findByBankTransactionIdIn(List.of(tx.getId()))).thenReturn(List.of(row));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(1L))).thenReturn(List.of());
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).invoiceNumber("INV00001").totalDue(8470.0).currency("EUR").dueDate(LocalDate.of(2020, 1, 1)).build()));

        BankTransactionSummary summary = mapper().toSummaries(List.of(tx)).get(0);

        assertEquals("Prince Boghara", summary.reviewedBy());
        assertEquals(when.atZone(ZoneId.systemDefault()).toInstant(), summary.reconciliationDate());
        assertEquals(1, summary.matchedInvoices().size());
        assertEquals("INV00001", summary.matchedInvoices().get(0).invoiceNumber());
        assertEquals("Belfius", summary.matchedInvoices().get(0).bankName());
    }

    @Test
    void toSummaries_unmatched_hasNoReviewFieldsAtAll() {
        BankTransactionSummary summary = mapper().toSummaries(List.of(transaction(BankTransactionStatus.UNMATCHED))).get(0);

        assertNull(summary.reviewedBy());
        assertNull(summary.reviewerComment());
        assertNull(summary.matchedInvoices());
        assertNull(summary.reconciliationDate());
    }

    @Test
    void alternativeInvoices_addSupplierNameAndDueDate_inCandidateOrder() {
        MatchCandidate first = new MatchCandidate(5L, "INV00002", "Amazon Marketplace Europe", LocalDate.of(2026, 9, 25), new BigDecimal("726.00"));
        MatchCandidate noDueDate = new MatchCandidate(6L, "INV00003", "", null, new BigDecimal("10.00"));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(5L, 6L))).thenReturn(List.of());
        when(supplierDocumentsRecordRepository.findAllById(List.of(5L, 6L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(6L).invoiceNumber("INV00003").totalDue(10.0).currency("EUR").build(),
                SupplierDocumentsRecord.builder().id(5L).invoiceNumber("INV00002").totalDue(726.0).currency("EUR").dueDate(LocalDate.of(2026, 9, 25)).build()));

        List<AlternativeInvoice> result = mapper().alternativeInvoices(List.of(first, noDueDate));

        assertEquals(2, result.size());
        assertEquals("INV00002", result.get(0).invoiceNumber());
        assertEquals("Amazon Marketplace Europe", result.get(0).supplierName());
        assertEquals(Instant.parse("2026-09-25T00:00:00Z"), result.get(0).dueDate());
        assertNull(result.get(1).supplierName());
        assertNull(result.get(1).dueDate());
    }

    @Test
    void toDetail_suggestedMatch_computesLiveSuggestionFields() {
        BankTransaction tx = transaction(BankTransactionStatus.SUGGESTED_MATCH); // amount 250.00, CREDIT
        MatchCandidate candidate = new MatchCandidate(1L, "INV-2026-001", "Customer ABC", LocalDate.of(2026, 9, 6), new BigDecimal("250.00"));
        when(candidateResolver.resolveCandidates(tx)).thenReturn(List.of(candidate));
        when(candidateResolver.treatAsCustomerInvoice(tx)).thenReturn(true);
        MatchSuggestion suggestion = new MatchSuggestion(90, List.of(new be.smobile.reconciliation.matching.InvoiceAllocation(1L, new BigDecimal("250.00"))),
                List.of(new MatchCriterion(MatchCriterionType.AMOUNT, true), new MatchCriterion(MatchCriterionType.PARTNER, true),
                        new MatchCriterion(MatchCriterionType.REFERENCE, true), new MatchCriterion(MatchCriterionType.DATE, false)));
        when(matchingEngine.suggest(eq(tx), any())).thenReturn(suggestion);
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(1L))).thenReturn(List.of());
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).invoiceNumber("INV-2026-001").totalDue(250.0).currency("EUR").dueDate(LocalDate.of(2026, 9, 6)).build()));

        BankTransactionDetail detail = mapper().toDetail(tx);

        assertEquals("suggested_match", detail.reconciliationStatus());
        assertEquals(90, detail.matchScore());
        assertEquals(List.of("Amount matches exactly", "Customer name matches", "Invoice reference INV-2026-001 detected"), detail.matchingCriteria());
        assertEquals(1, detail.scoringDetails().size());
        assertEquals("+50", detail.scoringDetails().getFirst().amountMatch());
        assertEquals(0, detail.allocatedAmount().compareTo(new BigDecimal("250.00")));
        assertEquals(tx.getAmount(), detail.paymentAmount());
        assertEquals(1, detail.matchedInvoices().size());
        assertNull(detail.reviewedBy());
    }

    private BankTransaction transaction(BankTransactionStatus status) {
        Instant now = Instant.now();
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .accountId(UUID.randomUUID())
                .bankName("Belfius")
                .sourceType(SourceType.PDF)
                .transactionDate(LocalDate.of(2026, 9, 5))
                .description("Customer ABC")
                .amount(new BigDecimal("250.00"))
                .direction(TransactionDirection.CREDIT)
                .reconciliationStatus(status)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}

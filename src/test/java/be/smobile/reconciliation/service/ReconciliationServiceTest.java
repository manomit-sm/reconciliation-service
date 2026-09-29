package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.InvoiceAllocation;
import be.smobile.reconciliation.matching.MatchCriterion;
import be.smobile.reconciliation.matching.MatchCriterionType;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.matching.MatchingEngine;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers only the "engine core" behavior that stays in Milestone 2 - suggest/classify/confirm.
 * Listing, search, and dashboard-summary formatting were Milestone 3 ("REST APIs") scope and
 * were removed from {@link ReconciliationService} along with the controller/DTOs.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;
    @Mock
    private ReconciliationRepository reconciliationRepository;
    @Mock
    private SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    @Mock
    private ReconciliationCandidateResolver candidateResolver;
    @Mock
    private MatchingEngine matchingEngine;

    private final MatchingProperties matchingProperties = new MatchingProperties();

    private ReconciliationService service() {
        return new ReconciliationService(bankTransactionRepository, reconciliationRepository, supplierDocumentsRecordRepository,
                candidateResolver, matchingEngine, matchingProperties);
    }

    @Test
    void classify_scoreAtOrAboveThreshold_isSuggestedMatch_belowIsUnmatched() {
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH,
                service().classify(new MatchSuggestion(70, List.of(), List.of())));
        assertEquals(BankTransactionStatus.UNMATCHED,
                service().classify(new MatchSuggestion(69, List.of(), List.of())));
    }

    /** 2026-09-19 client rule: "When the score is 98% or more... this transaction will not be reconciled manual." */
    @Test
    void classify_scoreAtOrAboveAutoReconcileThreshold_isReconciled() {
        assertEquals(BankTransactionStatus.RECONCILED,
                service().classify(new MatchSuggestion(98, List.of(), List.of())));
        assertEquals(BankTransactionStatus.RECONCILED,
                service().classify(new MatchSuggestion(100, List.of(), List.of())));
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH,
                service().classify(new MatchSuggestion(97, List.of(), List.of())));
    }

    /** Client feedback 2026-09-28 (point 6): an equal-amount match must reach "Suggested Match" even though 50 < the 70 threshold. */
    @Test
    void classify_exactAmountAlone_isSuggestedMatchEvenBelowTheScoreThreshold() {
        MatchSuggestion amountOnly = new MatchSuggestion(50, List.of(), List.of(new MatchCriterion(MatchCriterionType.AMOUNT, true)));
        MatchSuggestion partnerOnly = new MatchSuggestion(30, List.of(), List.of(new MatchCriterion(MatchCriterionType.AMOUNT, false)));

        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, service().classify(amountOnly));
        assertEquals(BankTransactionStatus.UNMATCHED, service().classify(partnerOnly));

        matchingProperties.setSuggestOnExactAmount(false);
        assertEquals(BankTransactionStatus.UNMATCHED, service().classify(amountOnly));
    }

    @Test
    void rematchUnmatched_promotesOnlyTheTransactionsThatNowMatch() {
        BankTransaction nowMatches = transaction();
        BankTransaction stillNothing = transaction();
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        when(bankTransactionRepository.findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end))
                .thenReturn(List.of(nowMatches, stillNothing));
        when(candidateResolver.resolveCandidates(List.of(nowMatches, stillNothing))).thenReturn(java.util.Map.of(
                nowMatches.getId(), List.of(), stillNothing.getId(), List.of()));
        when(matchingEngine.suggest(nowMatches, List.of())).thenReturn(new MatchSuggestion(80, List.of(new InvoiceAllocation(1L, BigDecimal.TEN)), List.of()));
        when(matchingEngine.suggest(stillNothing, List.of())).thenReturn(MatchSuggestion.none());

        int changed = service().rematchUnmatched(start, end);

        assertEquals(1, changed);
        assertEquals(BankTransactionStatus.SUGGESTED_MATCH, nowMatches.getReconciliationStatus());
        assertEquals(BankTransactionStatus.UNMATCHED, stillNothing.getReconciliationStatus());
        verify(bankTransactionRepository).save(nowMatches);
        verify(bankTransactionRepository, Mockito.never()).save(stillNothing);
        verify(reconciliationRepository, Mockito.never()).save(any());
    }

    @Test
    void rematchUnmatched_aPerfectMatchIsAutoReconciledLikeAnImport() {
        BankTransaction transaction = transaction();
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        when(bankTransactionRepository.findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end))
                .thenReturn(List.of(transaction));
        when(candidateResolver.resolveCandidates(List.of(transaction))).thenReturn(java.util.Map.of(transaction.getId(), List.of()));
        when(matchingEngine.suggest(transaction, List.of()))
                .thenReturn(new MatchSuggestion(100, List.of(new InvoiceAllocation(7L, new BigDecimal("250.00"))), List.of()));
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(7L))).thenReturn(List.of(SupplierDocumentsRecord.builder().id(7L).build()));

        service().rematchUnmatched(start, end);

        assertEquals(BankTransactionStatus.RECONCILED, transaction.getReconciliationStatus());
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r -> "auto-reconciliation-engine".equals(r.getCreatedBy())));
    }

    @Test
    void rematchUnmatched_nothingUnmatched_doesNoWork() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        when(bankTransactionRepository.findByReconciliationStatusAndTransactionDateBetween(BankTransactionStatus.UNMATCHED, start, end))
                .thenReturn(List.of());

        assertEquals(0, service().rematchUnmatched(start, end));
        verify(candidateResolver, Mockito.never()).resolveCandidates(ArgumentMatchers.<List<BankTransaction>>any());
    }

    @Test
    void autoConfirm_attributesTheReconciliationRowToTheEngineNotAHuman() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(7L)))
                .thenReturn(List.of(SupplierDocumentsRecord.builder().id(7L).build()));

        service().autoConfirm(transaction.getId(), List.of(new InvoiceAllocation(7L, new BigDecimal("99.00"))));

        assertEquals(BankTransactionStatus.RECONCILED, transaction.getReconciliationStatus());
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r -> "auto-reconciliation-engine".equals(r.getCreatedBy())
                && "Auto-reconciliation".equals(r.getCreatedByName())));
    }

    @Test
    void suggest_delegatesToCandidateResolverAndEngine() {
        BankTransaction transaction = transaction();
        when(candidateResolver.resolveCandidates(transaction)).thenReturn(List.of());
        MatchSuggestion expected = new MatchSuggestion(100, List.of(new InvoiceAllocation(1L, new BigDecimal("250.00"))), List.of());
        when(matchingEngine.suggest(transaction, List.of())).thenReturn(expected);

        assertEquals(expected, service().suggest(transaction));
    }

    @Test
    void confirmMatch_withExplicitAllocations_createsOneReconciliationRowAndMarksReconciled() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        SupplierDocumentsRecord invoice = SupplierDocumentsRecord.builder().id(7L).build();
        when(supplierDocumentsRecordRepository.findAllById(List.of(7L))).thenReturn(List.of(invoice));

        service().confirmMatch(transaction.getId(), List.of(new InvoiceAllocation(7L, new BigDecimal("99.00"))));

        assertEquals(BankTransactionStatus.RECONCILED, transaction.getReconciliationStatus());
        verify(matchingEngine, Mockito.never()).suggest(any(), any());
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getInvoiceIds().equals(List.of(7L))
                        && r.getAllocatedAmounts().size() == 1
                        && r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("99.00")) == 0
                        && r.getBankTransaction() == transaction
                        && "system".equals(r.getCreatedBy())));
        verify(bankTransactionRepository).save(transaction);
    }

    @Test
    void confirmMatch_withNoExplicitAllocations_confirmsTheCurrentLiveSuggestion() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(candidateResolver.resolveCandidates(transaction)).thenReturn(List.of());
        when(matchingEngine.suggest(transaction, List.of())).thenReturn(
                new MatchSuggestion(100, List.of(new InvoiceAllocation(1L, new BigDecimal("250.00"))), List.of()));
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L)))
                .thenReturn(List.of(SupplierDocumentsRecord.builder().id(1L).build()));

        service().confirmMatch(transaction.getId(), List.of());

        assertEquals(BankTransactionStatus.RECONCILED, transaction.getReconciliationStatus());
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("250.00")) == 0));
    }

    // Section 5.2/5.4 (1:N/N:N): one confirm call against several invoices is one Reconciliation
    // row whose invoiceIds/allocatedAmounts arrays hold every invoice in the group, in order -
    // see Reconciliation's own javadoc for why this replaced one-row-per-invoice 2026-09-15.
    @Test
    void scenario2_onePaymentToSeveralInvoices_isOneRowWithBothInvoiceIdsAndAmounts() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).build(),
                SupplierDocumentsRecord.builder().id(2L).build()));

        service().confirmMatch(transaction.getId(), List.of(
                new InvoiceAllocation(1L, new BigDecimal("400.00")),
                new InvoiceAllocation(2L, new BigDecimal("200.00"))));

        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getInvoiceIds().equals(List.of(1L, 2L))
                        && r.getAllocatedAmounts().equals(List.of(new BigDecimal("400.00"), new BigDecimal("200.00")))));
    }

    // Section 5.3 (N:1): 3 separate payments against the same invoice must produce 3 independent
    // Reconciliation rows that accumulate (400 + 300 + 300 = 1000), not overwrite one another -
    // exactly "BT001 -> INV001 -> 400, BT002 -> INV001 -> 300, BT003 -> INV001 -> 300" from the doc.
    @Test
    void scenario3_multiplePaymentsToOneInvoice_eachConfirmCallAddsItsOwnRow() {
        SupplierDocumentsRecord invoice = SupplierDocumentsRecord.builder().id(1L).build();
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L))).thenReturn(List.of(invoice));
        BankTransaction bt1 = transaction();
        BankTransaction bt2 = transaction();
        BankTransaction bt3 = transaction();
        when(bankTransactionRepository.findById(bt1.getId())).thenReturn(Optional.of(bt1));
        when(bankTransactionRepository.findById(bt2.getId())).thenReturn(Optional.of(bt2));
        when(bankTransactionRepository.findById(bt3.getId())).thenReturn(Optional.of(bt3));

        service().confirmMatch(bt1.getId(), List.of(new InvoiceAllocation(1L, new BigDecimal("400.00"))));
        service().confirmMatch(bt2.getId(), List.of(new InvoiceAllocation(1L, new BigDecimal("300.00"))));
        service().confirmMatch(bt3.getId(), List.of(new InvoiceAllocation(1L, new BigDecimal("300.00"))));

        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r -> r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("400.00")) == 0));
        verify(reconciliationRepository, Mockito.times(2))
                .save(ArgumentMatchers.argThat(r -> r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("300.00")) == 0));
        assertEquals(BankTransactionStatus.RECONCILED, bt1.getReconciliationStatus());
        assertEquals(BankTransactionStatus.RECONCILED, bt2.getReconciliationStatus());
        assertEquals(BankTransactionStatus.RECONCILED, bt3.getReconciliationStatus());
    }

    @Test
    void confirmMatch_nothingToConfirm_throws() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(candidateResolver.resolveCandidates(transaction)).thenReturn(List.of());
        when(matchingEngine.suggest(transaction, List.of())).thenReturn(MatchSuggestion.none());

        assertThrows(IllegalStateException.class, () -> service().confirmMatch(transaction.getId(), List.of()));
    }

    @Test
    void confirmMatch_unknownTransaction_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(bankTransactionRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service().confirmMatch(id, List.of()));
    }

    // There's no FK on invoice_ids to catch this for us (see Reconciliation's javadoc) - the
    // service has to check it itself before writing anything.
    @Test
    void confirmMatch_unknownInvoice_throwsNotFound() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(404L))).thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () ->
                service().confirmMatch(transaction.getId(), List.of(new InvoiceAllocation(404L, new BigDecimal("10.00")))));
        verify(reconciliationRepository, Mockito.never()).save(any());
    }

    // ---- API 7 (Approve Match / Reconcile Transaction) - reconcile(transactionId, invoiceIds) ----
    // The request carries only invoice ids, no amounts - see ReconcileRequest's javadoc for why.

    @Test
    void reconcile_singleInvoice_allocatesExactlyItsRemainingBalance() {
        BankTransaction transaction = transaction(); // amount 250.00
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(7L)))
                .thenReturn(List.of(SupplierDocumentsRecord.builder().id(7L).totalDue(250.0).build()));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(7L))).thenReturn(List.of());

        service().reconcile(transaction.getId(), List.of(7L));

        assertEquals(BankTransactionStatus.RECONCILED, transaction.getReconciliationStatus());
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getInvoiceIds().equals(List.of(7L))
                        && r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("250.00")) == 0));
    }

    @Test
    void reconcile_invoiceLargerThanTransaction_allocatesOnlyTheTransactionAmount() {
        BankTransaction transaction = transaction(); // amount 250.00
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(7L)))
                .thenReturn(List.of(SupplierDocumentsRecord.builder().id(7L).totalDue(1000.0).build()));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(7L))).thenReturn(List.of());

        service().reconcile(transaction.getId(), List.of(7L));

        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("250.00")) == 0));
    }

    @Test
    void reconcile_severalInvoices_consumesEachInOrderUntilTransactionAmountExhausted() {
        BankTransaction transaction = transaction(); // amount 250.00
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).totalDue(150.0).build(),
                SupplierDocumentsRecord.builder().id(2L).totalDue(1000.0).build()));
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(1L, 2L))).thenReturn(List.of());

        service().reconcile(transaction.getId(), List.of(1L, 2L));

        // invoice 1 consumed in full (150.00), invoice 2 only gets the 100.00 remainder.
        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getInvoiceIds().equals(List.of(1L, 2L))
                        && r.getAllocatedAmounts().get(0).compareTo(new BigDecimal("150.00")) == 0
                        && r.getAllocatedAmounts().get(1).compareTo(new BigDecimal("100.00")) == 0));
    }

    @Test
    void reconcile_alreadyFullyAllocatedInvoiceInTheMiddle_isSkipped() {
        BankTransaction transaction = transaction(); // amount 250.00
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        SupplierDocumentsRecord invoice2 = SupplierDocumentsRecord.builder().id(2L).totalDue(150.0).build();
        when(supplierDocumentsRecordRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                SupplierDocumentsRecord.builder().id(1L).totalDue(100.0).build(), invoice2));
        // confirmMatch's own assertInvoicesExist re-checks with just the invoice(s) that actually
        // ended up allocated (invoice 1 is skipped below, so only [2L] reaches it).
        when(supplierDocumentsRecordRepository.findAllById(List.of(2L))).thenReturn(List.of(invoice2));
        // invoice 1 already fully paid off by a prior reconciliation - nothing left to allocate.
        when(reconciliationRepository.sumAllocatedAmountsByInvoiceIds(List.of(1L, 2L)))
                .thenReturn(List.of(allocatedTotal(1L, new BigDecimal("100.00"))));

        service().reconcile(transaction.getId(), List.of(1L, 2L));

        verify(reconciliationRepository).save(ArgumentMatchers.argThat(r ->
                r.getInvoiceIds().equals(List.of(2L))
                        && r.getAllocatedAmounts().size() == 1
                        && r.getAllocatedAmounts().getFirst().compareTo(new BigDecimal("150.00")) == 0));
    }

    @Test
    void reconcile_emptyInvoiceIds_throwsBadRequest() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> service().reconcile(id, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service().reconcile(id, null));
    }

    @Test
    void reconcile_unknownInvoice_throwsNotFound_beforeTouchingTheTransaction() {
        UUID id = UUID.randomUUID();
        when(supplierDocumentsRecordRepository.findAllById(List.of(404L))).thenReturn(List.of());

        assertThrows(EntityNotFoundException.class, () -> service().reconcile(id, List.of(404L)));
        verify(bankTransactionRepository, Mockito.never()).findById(any());
    }

    private InvoiceAllocatedTotal allocatedTotal(Long invoiceId, BigDecimal total) {
        return new InvoiceAllocatedTotal() {
            @Override
            public Long getInvoiceId() {
                return invoiceId;
            }

            @Override
            public BigDecimal getTotal() {
                return total;
            }
        };
    }

    // ---- API 6 (Reopen Transaction for Matching) ----

    @Test
    void reopen_fromNeedsReview_movesToUnmatched() {
        BankTransaction transaction = transaction();
        transaction.setReconciliationStatus(BankTransactionStatus.NEEDS_REVIEW);
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        service().reopen(transaction.getId());

        assertEquals(BankTransactionStatus.UNMATCHED, transaction.getReconciliationStatus());
        verify(bankTransactionRepository).save(transaction);
    }

    @Test
    void reopen_notInNeedsReview_throwsConflict() {
        BankTransaction transaction = transaction(); // UNMATCHED by default
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        assertThrows(IllegalStateException.class, () -> service().reopen(transaction.getId()));
        verify(bankTransactionRepository, Mockito.never()).save(any());
    }

    // ---- API 8 (Mark Transaction for Review) ----

    @Test
    void markForReview_setsStatusReviewerAndTimestamp_reviewerIdNeverTakenFromTheCaller() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        service().markForReview(transaction.getId(), "Wrong amount");

        assertEquals(BankTransactionStatus.NEEDS_REVIEW, transaction.getReconciliationStatus());
        assertEquals("Wrong amount", transaction.getReviewerComment());
        // "system" - the local/dev fallback of ReconciliationService#currentUser, since there's
        // no authenticated JWT in this unit test - never taken from any method parameter.
        assertEquals("system", transaction.getReviewerId());
        assertNotNull(transaction.getReviewedAt());
        verify(bankTransactionRepository).save(transaction);
    }

    /** Client feedback 2026-09-28: "reviewed_by" is the reviewer's full name, captured from their own JWT when they act. */
    @Test
    void markForReview_capturesTheReviewersFullNameFromTheJwt() {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        authenticateAs(jwt -> jwt.subject("kc-user-1").claim("name", "Prince Boghara"));

        try {
            service().markForReview(transaction.getId(), "Wrong amount");
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("kc-user-1", transaction.getReviewerId());
        assertEquals("Prince Boghara", transaction.getReviewerName());
    }

    @Test
    void reviewerName_fallsBackThroughGivenAndFamilyNameThenUsernameThenSubject() {
        assertEquals("Prince Boghara", reviewerNameFor(jwt -> jwt.subject("s").claim("given_name", "Prince").claim("family_name", "Boghara")));
        assertEquals("pboghara", reviewerNameFor(jwt -> jwt.subject("s").claim("preferred_username", "pboghara")));
        assertEquals("s", reviewerNameFor(jwt -> jwt.subject("s")));
    }

    private String reviewerNameFor(java.util.function.Consumer<org.springframework.security.oauth2.jwt.Jwt.Builder> claims) {
        BankTransaction transaction = transaction();
        when(bankTransactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        authenticateAs(claims);
        try {
            service().markForReview(transaction.getId(), "x");
        } finally {
            SecurityContextHolder.clearContext();
        }
        return transaction.getReviewerName();
    }

    private void authenticateAs(java.util.function.Consumer<org.springframework.security.oauth2.jwt.Jwt.Builder> claims) {
        org.springframework.security.oauth2.jwt.Jwt.Builder builder = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none");
        claims.accept(builder);
        org.springframework.security.oauth2.jwt.Jwt jwt = builder.build();
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
    }

    private BankTransaction transaction() {
        return BankTransaction.builder()
                .id(UUID.randomUUID())
                .description("Customer ABC")
                .amount(new BigDecimal("250.00"))
                .direction(TransactionDirection.CREDIT)
                .transactionDate(LocalDate.of(2026, 9, 5))
                .reconciliationStatus(BankTransactionStatus.UNMATCHED)
                .build();
    }
}

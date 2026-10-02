package be.smobile.reconciliation.service;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.entity.Reconciliation;
import be.smobile.reconciliation.entity.SupplierDocumentsRecord;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.matching.MatchSuggestion;
import be.smobile.reconciliation.matching.MatchingCriteriaPresenter;
import be.smobile.reconciliation.matching.MatchingEngine;
import be.smobile.reconciliation.matching.MatchingProperties;
import be.smobile.reconciliation.model.dto.AlternativeInvoice;
import be.smobile.reconciliation.model.dto.BankTransactionDetail;
import be.smobile.reconciliation.model.dto.BankTransactionSummary;
import be.smobile.reconciliation.model.dto.InvoiceSummary;
import be.smobile.reconciliation.model.dto.MultiPaymentAllocation;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.model.enums.InvoiceStatus;
import be.smobile.reconciliation.model.enums.InvoiceType;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.InvoiceAllocatedTotal;
import be.smobile.reconciliation.repository.ReconciliationRepository;
import be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository;
import be.smobile.reconciliation.status.InvoiceStatusCalculator;
import be.smobile.reconciliation.status.InvoiceTypeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the REST response shapes ({@code BankTransactionSummary}/{@code BankTransactionDetail})
 * from the real entities - the "Frontend API Requirements" doc's (2026-09-16) API 3/4/7/8
 * response bodies. Kept out of {@link ReconciliationService}/{@code ReconciliationController}
 * itself so the engine-core service stays presentation-agnostic, the same separation already
 * used for {@code InvoiceStatusCalculator} (decides) vs. {@code InvoiceStatusPresenter} (formats).
 */
@Component
@RequiredArgsConstructor
public class BankTransactionDtoMapper {

    private final SupplierDocumentsRecordRepository supplierDocumentsRecordRepository;
    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationCandidateResolver candidateResolver;
    private final MatchingEngine matchingEngine;
    private final MatchingProperties matchingProperties;
    private final MatchingCriteriaPresenter criteriaPresenter;
    private final InvoiceStatusCalculator invoiceStatusCalculator;
    private final InvoiceTypeResolver invoiceTypeResolver;
    private final MultiPaymentAllocationService multiPaymentAllocationService;

    public BankTransactionSummary toSummary(BankTransaction tx) {
        return toSummaries(List.of(tx)).get(0);
    }

    /**
     * API 3's list rows. Client feedback 2026-09-28 (points 2/3): {@code needs_review} rows now
     * carry the reviewer's full name, comment and a date, and {@code reconciled} rows the
     * reviewer, date and matched invoices - the same enrichment API 4 already did per
     * transaction, so the list no longer needs a follow-up call per row. Done in batch: every
     * reconciled row's {@link Reconciliation}s and invoices are loaded with one query each for
     * the whole page, not one per row.
     */
    public List<BankTransactionSummary> toSummaries(List<BankTransaction> transactions) {
        List<UUID> reconciledIds = transactions.stream()
                .filter(tx -> tx.getReconciliationStatus() == BankTransactionStatus.RECONCILED)
                .map(BankTransaction::getId)
                .toList();
        Map<UUID, List<Reconciliation>> reconciliationsByTransaction = reconciledIds.isEmpty()
                ? Map.of()
                : reconciliationRepository.findByBankTransactionIdIn(reconciledIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getBankTransaction().getId()));

        List<Long> allInvoiceIds = reconciliationsByTransaction.values().stream()
                .flatMap(List::stream)
                .flatMap(r -> r.getInvoiceIds().stream())
                .distinct()
                .toList();
        Map<Long, InvoiceSummary> invoicesById = matchedInvoices(allInvoiceIds).stream()
                .collect(Collectors.toMap(InvoiceSummary::id, i -> i, (a, b) -> a));

        return transactions.stream()
                .map(tx -> toSummary(tx, reconciliationsByTransaction.getOrDefault(tx.getId(), List.of()), invoicesById))
                .toList();
    }

    private BankTransactionSummary toSummary(BankTransaction tx, List<Reconciliation> reconciliations, Map<Long, InvoiceSummary> invoicesById) {
        String reviewedBy = null;
        String reviewerComment = null;
        List<InvoiceSummary> matched = null;
        Instant reconciliationDate = null;

        if (tx.getReconciliationStatus() == BankTransactionStatus.NEEDS_REVIEW) {
            reviewedBy = displayName(tx.getReviewerName(), tx.getReviewerId());
            reviewerComment = tx.getReviewerComment();
            reconciliationDate = tx.getReviewedAt();
        } else if (tx.getReconciliationStatus() == BankTransactionStatus.RECONCILED) {
            Reconciliation latest = latestReconciliation(reconciliations);
            reviewedBy = latest != null
                    ? displayName(latest.getCreatedByName(), latest.getCreatedBy())
                    : displayName(tx.getReviewerName(), tx.getReviewerId());
            matched = reconciliations.stream()
                    .flatMap(r -> r.getInvoiceIds().stream())
                    .distinct()
                    .map(invoicesById::get)
                    .filter(java.util.Objects::nonNull)
                    .map(invoice -> invoice.withBankName(tx.getBankName()))
                    .toList();
            reconciliationDate = latest != null ? toInstant(latest.getReconciliationDate()) : null;
        }

        return new BankTransactionSummary(
                tx.getId(),
                tx.getAccountId(),
                tx.getStatement() != null ? tx.getStatement().getId() : null,
                tx.getSourceType().wireValue(),
                tx.getBankName(),
                tx.getExternalTransactionId(),
                tx.getTransactionDate(),
                tx.getDescription(),
                tx.getReference(),
                tx.getAmount(),
                tx.getDirection().wireValue(),
                tx.getReconciliationStatus().wireValue(),
                reviewedBy,
                reviewerComment,
                matched,
                tx.getCreatedAt(),
                tx.getUpdatedAt(),
                reconciliationDate);
    }

    private Reconciliation latestReconciliation(List<Reconciliation> reconciliations) {
        return reconciliations.stream()
                .filter(r -> r.getReconciliationDate() != null)
                .max(Comparator.comparing(Reconciliation::getReconciliationDate))
                .orElse(null);
    }

    /** The stored full name, or the raw user id for rows written before names were captured (see {@code BankTransaction#reviewerName}). */
    private String displayName(String name, String id) {
        return name != null && !name.isBlank() ? name : id;
    }

    /**
     * {@code Reconciliation.reconciliationDate} is a zone-less {@link LocalDateTime} written with
     * {@code LocalDateTime.now()}, i.e. in the server's own zone - read back in that same zone,
     * so the resulting instant is the moment it was actually recorded.
     */
    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    /** Dispatches by {@code reconciliationStatus} - see the three 4.1/4.2/4.3 branches below. Also enriches the two "live" (not yet reconciled) branches with a multi-payment-allocation suggestion, if one exists - see {@link MultiPaymentAllocationService}. */
    public BankTransactionDetail toDetail(BankTransaction tx) {
        BankTransactionDetail base = baseDetail(tx);
        return switch (tx.getReconciliationStatus()) {
            case RECONCILED -> withReconciledFields(base, tx);
            case NEEDS_REVIEW -> withReviewFields(base, tx);
            case SUGGESTED_MATCH -> withMultiPaymentAllocation(withSuggestionFields(base, tx), tx);
            case UNMATCHED -> withMultiPaymentAllocation(base, tx);
        };
    }

    private BankTransactionDetail baseDetail(BankTransaction tx) {
        return new BankTransactionDetail(
                tx.getId(), tx.getAccountId(), tx.getBankName(),
                tx.getStatement() != null ? tx.getStatement().getId() : null,
                tx.getSourceType().wireValue(), tx.getExternalTransactionId(),
                tx.getTransactionDate(), tx.getDescription(), tx.getReference(), tx.getAmount(),
                tx.getDirection().wireValue(), tx.getReconciliationStatus().wireValue(),
                null, null, null, null,
                null, null, null, null, null, null,
                tx.getCreatedAt(), tx.getUpdatedAt());
    }

    /** 4.1: matched invoices from every {@link Reconciliation} row against this transaction, plus who reconciled it and when. */
    private BankTransactionDetail withReconciledFields(BankTransactionDetail base, BankTransaction tx) {
        List<Reconciliation> rows = reconciliationRepository.findByBankTransactionId(tx.getId());
        List<Long> invoiceIds = rows.stream().flatMap(r -> r.getInvoiceIds().stream()).distinct().toList();
        Reconciliation latest = latestReconciliation(rows);
        // Reviewer = whoever confirmed the (latest) match, not whoever last flagged it for review -
        // a transaction can be flagged by one person, reopened, then reconciled by another.
        String reviewedBy = latest != null
                ? displayName(latest.getCreatedByName(), latest.getCreatedBy())
                : displayName(tx.getReviewerName(), tx.getReviewerId());

        return new BankTransactionDetail(
                base.id(), base.accountId(), base.bankName(), base.statementId(), base.sourceType(),
                base.externalTransactionId(), base.transactionDate(), base.description(), base.reference(),
                base.amount(), base.direction(), base.reconciliationStatus(),
                reviewedBy, tx.getReviewerComment(),
                latest != null ? toInstant(latest.getReconciliationDate()) : null,
                matchedInvoices(invoiceIds, tx.getBankName()),
                null, null, null, null, null, null,
                base.createdAt(), base.updatedAt());
    }

    /** 4.2: no matched invoices yet (the point of needs_review), just who flagged it, why, and when. */
    private BankTransactionDetail withReviewFields(BankTransactionDetail base, BankTransaction tx) {
        return new BankTransactionDetail(
                base.id(), base.accountId(), base.bankName(), base.statementId(), base.sourceType(),
                base.externalTransactionId(), base.transactionDate(), base.description(), base.reference(),
                base.amount(), base.direction(), base.reconciliationStatus(),
                displayName(tx.getReviewerName(), tx.getReviewerId()), tx.getReviewerComment(), tx.getReviewedAt(), null,
                null, null, null, null, null, null,
                base.createdAt(), base.updatedAt());
    }

    /** 4.3: live-recomputed suggestion (not persisted anywhere until confirmed) - same engine call {@code ReconciliationService#suggest} makes, but this also needs the underlying {@link MatchCandidate}s for matched_invoices/matching_criteria text. */
    private BankTransactionDetail withSuggestionFields(BankTransactionDetail base, BankTransaction tx) {
        List<MatchCandidate> candidates = candidateResolver.resolveCandidates(tx);
        MatchSuggestion suggestion = matchingEngine.suggest(tx, candidates);
        boolean customerInvoice = candidateResolver.treatAsCustomerInvoice(tx);

        List<Long> matchedIds = suggestion.allocations().stream().map(a -> a.invoiceId()).toList();
        Map<Long, MatchCandidate> candidatesById = candidates.stream()
                .collect(Collectors.toMap(MatchCandidate::invoiceId, c -> c, (a, b) -> a));
        List<MatchCandidate> matchedCandidates = matchedIds.stream().map(candidatesById::get)
                .filter(java.util.Objects::nonNull).toList();

        BigDecimal allocatedAmount = suggestion.allocations().stream()
                .map(a -> a.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BankTransactionDetail(
                base.id(), base.accountId(), base.bankName(), base.statementId(), base.sourceType(),
                base.externalTransactionId(), base.transactionDate(), base.description(), base.reference(),
                base.amount(), base.direction(), base.reconciliationStatus(),
                null, null, null, matchedInvoices(matchedIds),
                suggestion.confidenceScore(),
                criteriaPresenter.describeCriteria(suggestion, matchedCandidates, customerInvoice),
                criteriaPresenter.scoringDetails(suggestion, matchingProperties),
                tx.getAmount(), allocatedAmount, null,
                base.createdAt(), base.updatedAt());
    }

    /** See {@link MultiPaymentAllocationService} - rebuilds {@code detail} with {@code multiPaymentAllocation} populated when a group is found, unchanged otherwise. */
    private BankTransactionDetail withMultiPaymentAllocation(BankTransactionDetail detail, BankTransaction tx) {
        return multiPaymentAllocationService.findGroup(tx)
                .map(group -> new BankTransactionDetail(
                        detail.id(), detail.accountId(), detail.bankName(), detail.statementId(), detail.sourceType(),
                        detail.externalTransactionId(), detail.transactionDate(), detail.description(), detail.reference(),
                        detail.amount(), detail.direction(), detail.reconciliationStatus(),
                        detail.reviewedBy(), detail.reviewerComment(), detail.reconciliationDate(), detail.matchedInvoices(),
                        detail.matchScore(), detail.matchingCriteria(), detail.scoringDetails(), detail.paymentAmount(),
                        detail.allocatedAmount(), toWireAllocation(group),
                        detail.createdAt(), detail.updatedAt()))
                .orElse(detail);
    }

    /** Assigns each payment a display label (A, B, C...) in payment order, then looks up invoice numbers for the allocation lines. */
    private MultiPaymentAllocation toWireAllocation(MultiPaymentAllocationService.PaymentGroup group) {
        Map<UUID, String> labelByPaymentId = new LinkedHashMap<>();
        List<MultiPaymentAllocation.PaymentSummary> payments = new ArrayList<>();
        for (int i = 0; i < group.payments().size(); i++) {
            BankTransaction payment = group.payments().get(i);
            String label = String.valueOf((char) ('A' + i));
            labelByPaymentId.put(payment.getId(), label);
            payments.add(new MultiPaymentAllocation.PaymentSummary(payment.getId(), label, payment.getTransactionDate(), payment.getAmount()));
        }

        List<Long> invoiceIds = group.allocations().stream()
                .map(MultiPaymentAllocationService.PaymentAllocation::invoiceId).distinct().toList();
        boolean customerSide = !group.payments().isEmpty() && group.payments().get(0).getDirection() == TransactionDirection.CREDIT;
        Map<Long, String> invoiceNumbers = new java.util.HashMap<>();
        Map<Long, String> supplierNames = new java.util.HashMap<>();
        supplierDocumentsRecordRepository.findAllById(invoiceIds).forEach(r -> {
            invoiceNumbers.put(r.getId(), InvoiceNumbers.of(r));
            supplierNames.put(r.getId(), blankToNull(candidateResolver.partnerName(r, customerSide)));
        });

        List<MultiPaymentAllocation.AllocationLine> lines = group.allocations().stream()
                .map(a -> new MultiPaymentAllocation.AllocationLine(
                        labelByPaymentId.get(a.payment().getId()), a.invoiceId(), invoiceNumbers.get(a.invoiceId()), supplierNames.get(a.invoiceId()), a.amount()))
                .toList();

        BigDecimal totalPayments = payments.stream().map(MultiPaymentAllocation.PaymentSummary::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAllocated = lines.stream().map(MultiPaymentAllocation.AllocationLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MultiPaymentAllocation(payments, lines, totalPayments, totalAllocated, totalPayments.subtract(totalAllocated));
    }

    /**
     * Public so {@code ReconciliationController} can reuse this exact mapping for API 5's
     * alternative-matches list, not just {@code matched_invoices} here. Preserves
     * {@code invoiceIds}'s own order (unlike {@code findAllById}, whose result order is
     * unspecified) - matters for API 5, where the caller already paginated candidates in a
     * specific order before this is called; unmatched ids are silently skipped rather than
     * failing the whole response over one stale/missing invoice.
     */
    public List<InvoiceSummary> matchedInvoices(List<Long> invoiceIds, String bankName) {
        return matchedInvoices(invoiceIds).stream().map(invoice -> invoice.withBankName(bankName)).toList();
    }

    /**
     * API 5's rows: {@link #matchedInvoices}'s fields plus the counterparty's name and due date
     * (client feedback 2026-09-28, point 1). The name comes from the candidate itself - already
     * resolved by {@link ReconciliationCandidateResolver#partnerName} for this transaction's
     * direction (customer for money in, supplier for money out) - rather than re-derived here.
     * Order follows {@code candidates}; a candidate whose invoice can't be loaded is skipped.
     */
    public List<AlternativeInvoice> alternativeInvoices(List<MatchCandidate> candidates) {
        Map<Long, InvoiceSummary> invoicesById = matchedInvoices(candidates.stream().map(MatchCandidate::invoiceId).toList()).stream()
                .collect(Collectors.toMap(InvoiceSummary::id, i -> i, (a, b) -> a));
        return candidates.stream()
                .filter(c -> invoicesById.containsKey(c.invoiceId()))
                .map(c -> {
                    InvoiceSummary invoice = invoicesById.get(c.invoiceId());
                    return new AlternativeInvoice(
                            invoice.id(), invoice.invoiceNumber(), invoice.amount(), invoice.currency(), invoice.type(), invoice.status(),
                            c.partnerName() == null || c.partnerName().isBlank() ? null : c.partnerName(),
                            c.dueDate() == null ? null : c.dueDate().atStartOfDay(ZoneOffset.UTC).toInstant());
                })
                .toList();
    }

    public List<InvoiceSummary> matchedInvoices(List<Long> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> allocated = reconciliationRepository.sumAllocatedAmountsByInvoiceIds(invoiceIds).stream()
                .collect(Collectors.toMap(InvoiceAllocatedTotal::getInvoiceId, InvoiceAllocatedTotal::getTotal));
        Map<Long, SupplierDocumentsRecord> recordsById = supplierDocumentsRecordRepository.findAllById(invoiceIds).stream()
                .collect(Collectors.toMap(SupplierDocumentsRecord::getId, r -> r));
        return invoiceIds.stream()
                .map(recordsById::get)
                .filter(java.util.Objects::nonNull)
                .map(record -> toInvoiceSummary(record, allocated.getOrDefault(record.getId(), BigDecimal.ZERO)))
                .toList();
    }

    private InvoiceSummary toInvoiceSummary(SupplierDocumentsRecord record, BigDecimal paidAmount) {
        InvoiceType type = invoiceTypeResolver.resolve(record);
        return new InvoiceSummary(
                record.getId(), InvoiceNumbers.of(record), record.getTotalDue(), record.getCurrency(),
                type == InvoiceType.CUSTOMER ? "credit" : "debit",
                invoiceStatusWireValue(record, type, paidAmount),
                null, record.getDescription(), record.getInvoiceDate(),
                blankToNull(candidateResolver.partnerName(record, type == InvoiceType.CUSTOMER)));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * {@link InvoiceStatusCalculator#calculate} requires a non-null {@code dueDate} (see its own
     * javadoc: callers must already know the record is open) - {@code SupplierDocumentsRecord.dueDate}
     * is nullable in the real table, so a missing one falls back to just the two branches that
     * don't need it (PAID/PARTIALLY_PAID) rather than throwing or guessing a due date.
     */
    private String invoiceStatusWireValue(SupplierDocumentsRecord record, InvoiceType type, BigDecimal paidAmount) {
        BigDecimal totalDue = record.getTotalDue() == null ? BigDecimal.ZERO : BigDecimal.valueOf(record.getTotalDue());
        BigDecimal balance = totalDue.subtract(paidAmount);
        if (record.getDueDate() == null) {
            if (balance.compareTo(BigDecimal.ZERO) <= 0) {
                return InvoiceStatus.PAID.name().toLowerCase(Locale.ROOT);
            }
            return paidAmount.compareTo(BigDecimal.ZERO) > 0 ? InvoiceStatus.PARTIALLY_PAID.name().toLowerCase(Locale.ROOT) : null;
        }
        InvoiceStatus status = invoiceStatusCalculator.calculate(type, paidAmount, balance, record.getDueDate(), LocalDate.now());
        return status.name().toLowerCase(Locale.ROOT);
    }
}

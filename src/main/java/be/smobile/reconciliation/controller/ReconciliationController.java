package be.smobile.reconciliation.controller;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.imports.BankAccountIdParser;
import be.smobile.reconciliation.imports.BankStatementUploadService;
import be.smobile.reconciliation.imports.ImportResult;
import be.smobile.reconciliation.matching.MatchCandidate;
import be.smobile.reconciliation.model.dto.AlternativeInvoice;
import be.smobile.reconciliation.model.dto.AlternativeMatchesResponse;
import be.smobile.reconciliation.model.dto.BankStatementUploadResponse;
import be.smobile.reconciliation.model.dto.BankTransactionDetail;
import be.smobile.reconciliation.model.dto.BankTransactionListResponse;
import be.smobile.reconciliation.model.dto.BankTransactionSummary;
import be.smobile.reconciliation.model.dto.Pagination;
import be.smobile.reconciliation.model.dto.ReconcileRequest;
import be.smobile.reconciliation.model.dto.ReconciliationSummaryResponse;
import be.smobile.reconciliation.model.dto.ReopenResponse;
import be.smobile.reconciliation.model.dto.ReviewRequest;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import be.smobile.reconciliation.repository.BankTransactionRepository;
import be.smobile.reconciliation.repository.BankTransactionSpecifications;
import be.smobile.reconciliation.service.BankTransactionDtoMapper;
import be.smobile.reconciliation.service.MultiPaymentAllocationService;
import be.smobile.reconciliation.service.ReconciliationCandidateResolver;
import be.smobile.reconciliation.service.ReconciliationDashboardService;
import be.smobile.reconciliation.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The 8 endpoints of the 2026-09-16 "Frontend API Requirements" doc. No class-level
 * {@code @RequestMapping} prefix: this service's own {@code server.servlet.context-path} is
 * already {@code /reconciliation} (see application.yml), so mapping e.g. {@code "/summary"}
 * here already serves at {@code /reconciliation/summary} - exactly API 1's documented path -
 * without doubling the segment the way re-adding {@code /reconciliation} to every mapping here
 * would. The doc's own API 2-8 examples don't repeat that segment either, consistent with this
 * reading; only API 1's own example happens to spell it out explicitly.
 * <p>
 * Thin by design: every method here does request-shape work (query params, pagination, status
 * codes) and delegates the actual decision-making to {@link ReconciliationService}/
 * {@link ReconciliationDashboardService}/{@link BankStatementUploadService}, with
 * {@link BankTransactionDtoMapper} doing entity-to-DTO shaping.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Reconciliation", description = "Bank statement import, transaction listing and invoice-matching workflow for the reconciliation dashboard.")
@ApiResponse(responseCode = "500", description = "Unexpected internal error.",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class ReconciliationController {

    private final ReconciliationDashboardService dashboardService;
    private final BankStatementUploadService uploadService;
    private final BankTransactionRepository bankTransactionRepository;
    private final ReconciliationCandidateResolver candidateResolver;
    private final ReconciliationService reconciliationService;
    private final BankTransactionDtoMapper dtoMapper;
    private final MultiPaymentAllocationService multiPaymentAllocationService;

    @Operation(summary = "Get reconciliation summary", description = "API 1. Dashboard counters for a period: unmatched/suggested-match/needs-review "
            + "transaction counts plus overdue customer and supplier invoice counts. Select the period with startDate+endDate (an explicit "
            + "range, both inclusive) or with year+month (one calendar month); if both forms are sent, startDate+endDate win.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Summary computed successfully."),
            @ApiResponse(responseCode = "400", description = "Neither startDate+endDate nor year+month was supplied, or the month is not 1-12.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/summary")
    public ReconciliationSummaryResponse summary(
            @Parameter(description = "Start of an explicit date range (inclusive, ISO yyyy-MM-dd). Requires endDate; together they take priority over year/month.", example = "2026-09-25")
            @RequestParam(required = false) LocalDate startDate,
            @Parameter(description = "End of an explicit date range (inclusive, ISO yyyy-MM-dd). Requires startDate.", example = "2026-10-02")
            @RequestParam(required = false) LocalDate endDate,
            @Parameter(description = "Calendar year, e.g. 2026 - used with month when startDate/endDate are not both given", example = "2026") @RequestParam(required = false) Integer year,
            @Parameter(description = "Calendar month (1-12) - used with year when startDate/endDate are not both given", example = "9") @RequestParam(required = false) Integer month) {
        if (startDate != null && endDate != null) {
            refreshMatches(null, null, startDate, endDate);
            return dashboardService.summary(startDate, endDate);
        }
        if (year != null && month != null) {
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException("month must be between 1 and 12, was " + month);
            }
            refreshMatches(year, month);
            return dashboardService.summary(year, month);
        }
        throw new IllegalArgumentException("Provide startDate and endDate, or year and month");
    }

    @Operation(summary = "Upload bank statement", description = "API 2. Uploads a bank statement PDF: computes its MD5 for duplicate detection, "
            + "stores it in S3, sends it to the AI extraction microservice, then imports the extracted transactions. "
            + "{@code accountId} identifies which of Pilim's bank accounts the statement belongs to - a required addition "
            + "beyond the doc's own minimal example, since this service has no bank-accounts table of its own.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Statement accepted and transactions imported."),
            @ApiResponse(responseCode = "400", description = "accountId is neither a UUID nor a numeric account id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "This exact file (by MD5) has already been imported.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "The S3 upload or the AI extraction call failed.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(value = "/bank-statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BankStatementUploadResponse uploadBankStatement(
            @Parameter(description = "Bank account the statement belongs to - a UUID or the numeric account id (e.g. 9000000447)", required = true, example = "9000000447")
            @RequestParam String accountId,
            @Parameter(description = "The bank statement PDF file", required = true) @RequestParam("file") MultipartFile file) {
        ImportResult result = uploadService.upload(BankAccountIdParser.parse(accountId), file);
        if (result.duplicateFile()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This exact bank statement file has already been imported");
        }
        return BankStatementUploadResponse.succeeded();
    }

    @Operation(summary = "Get bank transactions", description = "API 3. Paginated, filterable list of bank transactions, newest first. "
            + "status=all (or omitted) returns every transaction regardless of status. needs_review rows carry reviewed_by (the reviewer's full name), "
            + "reviewer_comment and reconciliation_date; reconciled rows carry reviewed_by, reconciliation_date and matched_invoices (each with the bank_name). "
            + "startDate/endDate, when both given, replace year/month as the date filter - use them for a range that isn't a single whole calendar month.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of transactions returned."),
            @ApiResponse(responseCode = "400", description = "Unknown 'status' value.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/transactions")
    public BankTransactionListResponse getTransactions(
            @Parameter(description = "Filter to transactions in this calendar year - ignored when startDate/endDate are both given") @RequestParam(required = false) Integer year,
            @Parameter(description = "Filter to transactions in this calendar month (1-12) - ignored when startDate/endDate are both given") @RequestParam(required = false) Integer month,
            @Parameter(description = "Filter by reconciliation status; 'all' (or omitted) applies no status filter", schema = @Schema(allowableValues = {"all", "unmatched", "suggested_match", "needs_review", "reconciled"}))
            @RequestParam(required = false) String status,
            @Parameter(description = "Case-insensitive search over the transaction description") @RequestParam(required = false) String search,
            @Parameter(description = "1-based page number", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size", example = "20") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Start of an explicit date range (inclusive, ISO yyyy-MM-dd). Requires endDate; together they take priority over year/month.", example = "2026-09-01")
            @RequestParam(required = false) LocalDate startDate,
            @Parameter(description = "End of an explicit date range (inclusive, ISO yyyy-MM-dd). Requires startDate.", example = "2026-09-20")
            @RequestParam(required = false) LocalDate endDate) {
        BankTransactionStatus statusFilter = parseStatus(status);
        refreshMatches(year, month, startDate, endDate);
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), limit, Sort.by(Sort.Direction.DESC, "transactionDate"));
        Page<BankTransaction> result = bankTransactionRepository.findAll(
                BankTransactionSpecifications.withFilters(year, month, startDate, endDate, statusFilter, search), pageable);

        List<BankTransactionSummary> items = dtoMapper.toSummaries(result.getContent());
        return new BankTransactionListResponse(items, Pagination.of(page, limit, result.getTotalElements()));
    }

    @Operation(summary = "Get bank transaction details", description = "API 4. Full detail for one transaction. The response shape depends on the "
            + "transaction's current status: reconciled/needs_review carry reviewer and matched-invoice fields, suggested_match carries live "
            + "scoring fields instead - fields that don't apply to the current status are omitted. "
            + "Note the singular path segment ('/transaction/{id}') - matches the doc's own literal example, unlike every other endpoint here.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction found and returned."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/transaction/{id}")
    public BankTransactionDetail getTransaction(@Parameter(description = "Bank transaction id", required = true) @PathVariable UUID id) {
        return dtoMapper.toDetail(findTransactionOrThrow(id));
    }

    @Operation(summary = "Get alternative invoice matches", description = "API 5. Paginated, searchable list of invoices this transaction could "
            + "alternatively be reconciled against, beyond the system's own top suggestion. Each row includes the invoice's supplier_name and dueDate. "
            + "search matches either the invoice number or the supplier_name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of candidate invoices returned."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/transactions/{transactionId}/alternative-matches")
    public AlternativeMatchesResponse alternativeMatches(
            @Parameter(description = "Bank transaction id", required = true) @PathVariable UUID transactionId,
            @Parameter(description = "Case-insensitive search over the invoice number or the supplier name") @RequestParam(required = false) String search,
            @Parameter(description = "1-based page number", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "Page size", example = "20") @RequestParam(defaultValue = "20") int limit) {
        BankTransaction transaction = findTransactionOrThrow(transactionId);
        List<MatchCandidate> candidates = candidateResolver.resolveCandidates(transaction);
        if (search != null && !search.isBlank()) {
            // Client feedback 2026-09-29 ("Find Alternatives Search Enhancement"): a user
            // looking for an invoice often remembers the supplier's name, not its number.
            String needle = search.trim().toLowerCase(Locale.ROOT);
            candidates = candidates.stream()
                    .filter(c -> containsIgnoreCase(c.invoiceNumber(), needle) || containsIgnoreCase(c.partnerName(), needle))
                    .toList();
        }

        int total = candidates.size();
        int fromIndex = Math.min(Math.max(page - 1, 0) * limit, total);
        int toIndex = Math.min(fromIndex + limit, total);

        List<AlternativeInvoice> data = dtoMapper.alternativeInvoices(candidates.subList(fromIndex, toIndex));
        return new AlternativeMatchesResponse(data, Pagination.of(page, limit, total));
    }

    @Operation(summary = "Reopen transaction for matching", description = "API 6. Moves a 'needs_review' transaction back to 'unmatched' so it "
            + "re-enters the normal matching flow.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction reopened."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Transaction is not currently 'needs_review'.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/transactions/{transactionId}/reopen")
    public ReopenResponse reopen(@Parameter(description = "Bank transaction id", required = true) @PathVariable UUID transactionId) {
        BankTransaction transaction = reconciliationService.reopen(transactionId);
        return new ReopenResponse(transaction.getId(), transaction.getReconciliationStatus().wireValue());
    }

    @Operation(summary = "Approve match / reconcile transaction", description = "API 7. Confirms a reconciliation against one or more invoices - "
            + "either accepting the system-suggested match or a manually-selected set. Only invoice ids are supplied; per-invoice allocation "
            + "amounts are computed server-side against each invoice's current remaining balance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction reconciled; updated transaction returned."),
            @ApiResponse(responseCode = "400", description = "invoiceIds was missing or empty.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No transaction, or no invoice, with a given id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Transaction is not in a reconcilable state.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "501", description = "expenseIds was supplied - the employee expense flow is not implemented yet.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/transactions/{transactionId}/reconcile")
    public BankTransactionDetail reconcile(
            @Parameter(description = "Bank transaction id", required = true) @PathVariable UUID transactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Invoice ids to reconcile against", required = true) @RequestBody ReconcileRequest request) {
        if (request.expenseIds() != null && !request.expenseIds().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Reconciling against expenses is not yet supported - the Employee expense flow is a separate, still-unspecified Milestone 3 item");
        }
        if (request.invoiceIds() == null || request.invoiceIds().isEmpty()) {
            throw new IllegalArgumentException("invoiceIds must not be empty");
        }
        BankTransaction transaction = reconciliationService.reconcile(transactionId, request.invoiceIds());
        return dtoMapper.toDetail(transaction);
    }

    @Operation(summary = "Approve multi-payment allocation", description = "Not part of the formal API doc - a best-effort addition covering the "
            + "\"Multi-payment allocation\" flow from the client's 2026-09-19 UI screenshots (see MultiPaymentAllocationService's javadoc for the "
            + "full reasoning). Reconciles every payment in transactionId's currently-suggested payment group against its share of the invoices "
            + "in one action - equivalent to calling API 7 once per payment, but atomically and without the caller having to compute each "
            + "payment's own split. The group is recomputed server-side rather than trusting client-supplied numbers, so a suggestion that's gone "
            + "stale since it was last fetched (an invoice or sibling payment changed) fails with 409 instead of reconciling numbers that no "
            + "longer add up.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every payment in the group reconciled; the given transaction's updated detail is returned."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "No multi-payment allocation is currently available for this transaction.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/transactions/{transactionId}/reconcile-group")
    public BankTransactionDetail reconcileGroup(@Parameter(description = "Any bank transaction id belonging to the group", required = true) @PathVariable UUID transactionId) {
        BankTransaction transaction = multiPaymentAllocationService.approveGroup(transactionId);
        return dtoMapper.toDetail(transaction);
    }

    @Operation(summary = "Mark multi-payment group for review", description = "Not part of the formal API doc - a best-effort addition covering the "
            + "\"Multi-payment allocation\" panel's own \"Mark for review\" button (see MultiPaymentAllocationService's javadoc). Flags every "
            + "payment in transactionId's currently-suggested payment group as needs_review with the same comment, in one action - equivalent to "
            + "calling API 8 once per payment. The group is recomputed server-side, same staleness handling as reconcile-group.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every payment in the group marked for review; the given transaction's updated detail is returned."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "No multi-payment allocation is currently available for this transaction.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/transactions/{transactionId}/review-group")
    public BankTransactionDetail reviewGroup(
            @Parameter(description = "Any bank transaction id belonging to the group", required = true) @PathVariable UUID transactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Reviewer comment explaining why this group needs review", required = true) @RequestBody ReviewRequest request) {
        BankTransaction transaction = multiPaymentAllocationService.markGroupForReview(transactionId, request.reviewerComment());
        return dtoMapper.toDetail(transaction);
    }

    @Operation(summary = "Mark transaction for review", description = "API 8. Flags a transaction as 'needs_review' with a reviewer comment, "
            + "for cases the automatic matching can't confidently resolve. The reviewer is derived from the authenticated user, not the request body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transaction marked for review; updated transaction returned."),
            @ApiResponse(responseCode = "404", description = "No transaction with this id.",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/transactions/{transactionId}/review")
    @ResponseStatus(HttpStatus.OK)
    public BankTransactionDetail review(
            @Parameter(description = "Bank transaction id", required = true) @PathVariable UUID transactionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Reviewer comment explaining why this needs review", required = true) @RequestBody ReviewRequest request) {
        BankTransaction transaction = reconciliationService.markForReview(transactionId, request.reviewerComment());
        return dtoMapper.toDetail(transaction);
    }

    private BankTransaction findTransactionOrThrow(UUID id) {
        return bankTransactionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Bank transaction " + id + " not found"));
    }

    /** API 1 (no startDate/endDate of its own) - always the year/month range. */
    private void refreshMatches(Integer year, Integer month) {
        refreshMatches(year, month, null, null);
    }

    /**
     * Re-runs matching over the (startDate/endDate, if given, else year/month) window's
     * still-unmatched transactions before the dashboard/list reads them - see
     * {@link ReconciliationService#rematchUnmatched} for why (expenses created after a
     * statement was imported). Best-effort: if it fails, the caller still gets whatever was
     * last classified rather than the whole request failing.
     */
    private void refreshMatches(Integer year, Integer month, LocalDate startDate, LocalDate endDate) {
        LocalDate start = startDate;
        LocalDate end = endDate;
        if (start == null || end == null) {
            if (year == null || month == null) {
                return;
            }
            YearMonth ym = YearMonth.of(year, month);
            start = ym.atDay(1);
            end = ym.atEndOfMonth();
        }
        try {
            reconciliationService.rematchUnmatched(start, end);
        } catch (RuntimeException e) {
            log.warn("Re-matching unmatched transactions for {}..{} failed; serving last known statuses", start, end, e);
        }
    }

    private boolean containsIgnoreCase(String value, String needleLowerCase) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLowerCase);
    }

    /** {@code reconciliation_status}'s wire value (e.g. {@code "suggested_match"}) back to the enum - a bad value is a 400, not a 500. */
    private BankTransactionStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return BankTransactionStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status + "'");
        }
    }
}

package be.smobile.reconciliation.exception;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Central exception-to-HTTP-response mapping - same {@code ProblemDetail}-based pattern as
 * moac-doc-gen-service's own {@code GlobalExceptionHandler}, adapted to what this controller's
 * own code can actually throw:
 * <ul>
 *   <li>{@link EntityNotFoundException} - an unknown transaction/invoice id (e.g.
 *       {@code ReconciliationService.findTransaction}/{@code assertInvoicesExist}) - 404.</li>
 *   <li>{@link IllegalArgumentException} - a malformed request the caller sent (e.g. an empty
 *       {@code invoiceIds}) - 400.</li>
 *   <li>{@link IllegalStateException} - the request is well-formed but not valid for this
 *       transaction's <i>current</i> state (e.g. reopening something that isn't
 *       {@code needs_review}, or confirming with nothing to confirm) - 409, not 500: this is the
 *       caller's timing/state mismatch, not an internal failure.</li>
 *   <li>{@link UnsupportedOperationException} - a real but not-yet-implemented request shape
 *       (currently only {@code expenseIds} on API 7 - "Employee expense flow" is its own,
 *       still-unspecified Milestone 3 bullet) - 501, distinct from a plain 400 so a caller can
 *       tell "not built yet" apart from "malformed".</li>
 *   <li>{@link SdkException} - the S3 upload failed ({@code BankStatementUploadService}) - 502,
 *       this service acting as a client to a failed downstream dependency.</li>
 *   <li>{@link RestClientException} - the AI extraction microservice call failed
 *       ({@code BankStatementExtractionClient}) - 502, same reasoning.</li>
 *   <li>{@link Exception} - final fallback for anything unanticipated - 500. Never exposes the
 *       raw exception message to the caller (only logged server-side).</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        log.warn("Not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException ex) {
        log.warn("Conflict with current transaction state: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ProblemDetail handleNotImplemented(UnsupportedOperationException ex) {
        log.warn("Not yet implemented: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    }

    @ExceptionHandler(SdkException.class)
    public ProblemDetail handleAwsFailure(SdkException ex) {
        log.error("AWS S3 request failed", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Failed to store the uploaded bank statement in S3");
        problem.setTitle("Upstream storage failure");
        return problem;
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleUpstreamServiceFailure(RestClientException ex) {
        log.error("AI extraction microservice call failed", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "The AI extraction service is unavailable or returned an error");
        problem.setTitle("Upstream service failure");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Unexpected error");
        return problem;
    }
}

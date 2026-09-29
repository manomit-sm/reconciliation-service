package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API 8 ("Mark Transaction for Review") request body. No {@code reviewer_id} field - per the
 * doc's own "Important" note, the backend determines that from the authenticated user, the same
 * {@code currentUser()} mechanism {@code ReconciliationService} already uses for
 * {@code Reconciliation.createdBy}.
 */
@Schema(description = "Reason a transaction is being flagged for review.")
public record ReviewRequest(
        @Schema(description = "Free-text explanation of why this transaction needs review", example = "Wrong amount", required = true)
        @JsonProperty("reviewer_comment") String reviewerComment) {
}

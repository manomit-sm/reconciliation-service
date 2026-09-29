package be.smobile.reconciliation.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * API 6 ("Reopen Transaction for Matching") response. The doc's own example shows a bare
 * integer ({@code "id": 1}) but every other example in the same doc uses an empty-string
 * placeholder for this field ({@code "id": ""}) consistent with this service's real
 * {@link UUID} ids - the "1" is read as sloppy example authoring, not a type spec, so {@code id}
 * is a string here like everywhere else.
 */
@Schema(description = "Result of reopening a transaction for matching.")
public record ReopenResponse(
        @Schema(description = "Bank transaction id") UUID id,
        @Schema(description = "New reconciliation status", example = "unmatched") String status) {
}

package be.smobile.reconciliation.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of API 4.3's {@code scoring_details} array (always exactly one element in this
 * service's responses, matching the doc's own example) - each criterion's earned points, as a
 * signed string ({@code "+50"}) when met or {@code "0"} when not, per
 * {@code MatchingCriteriaPresenter}.
 */
@Schema(description = "Per-criterion point breakdown of a suggested match's score.")
public record ScoringDetail(
        @Schema(description = "Points earned for amount matching", example = "+50") @JsonProperty("amount_match") String amountMatch,
        @Schema(description = "Points earned for partner name matching", example = "+30") @JsonProperty("partner_match") String partnerMatch,
        @Schema(description = "Points earned for invoice reference matching", example = "0") @JsonProperty("reference_match") String referenceMatch,
        @Schema(description = "Points earned for date matching", example = "0") @JsonProperty("date_match") String dateMatch) {
}

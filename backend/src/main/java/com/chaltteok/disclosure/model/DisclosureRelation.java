package com.chaltteok.disclosure.model;

import com.chaltteok.disclosure.model.enums.RelationType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 정정·후속공시 관계. docs/data-model.md §6.
 */
public record DisclosureRelation(
        @JsonProperty(required = true)
        @NotBlank
        String relationId,

        @JsonProperty(required = true)
        @NotBlank
        String eventKey,

        @JsonProperty(required = true)
        @NotBlank
        String originalDocId,

        @JsonProperty(required = true)
        @NotBlank
        String relatedDocId,

        @JsonProperty(required = true)
        @NotNull
        RelationType relationType,

        @JsonProperty(required = true)
        String previousStatus,

        @JsonProperty(required = true)
        String currentStatus,

        @JsonProperty(required = true)
        @NotNull
        List<@Valid ChangedField> changedFields,

        @JsonProperty(required = true)
        @NotEmpty
        List<@NotBlank String> evidenceChunkIds
) {
}

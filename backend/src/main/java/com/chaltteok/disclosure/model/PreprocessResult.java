package com.chaltteok.disclosure.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Python 전처리 출력 최상위 구조. docs/data-model.md §2.6.
 * JPA Entity가 아니라 preprocessed_documents.jsonl Jackson 역직렬화용 순수 모델이다.
 */
public record PreprocessResult(
        @JsonProperty(required = true)
        @NotBlank
        String schemaVersion,

        @JsonProperty(required = true)
        @NotBlank
        String parserVersion,

        @JsonProperty(required = true)
        @NotNull
        @Valid
        DisclosureDocument document,

        @JsonProperty(required = true)
        @NotNull
        @NotEmpty
        List<@Valid EvidenceChunk> evidenceChunks,

        @JsonProperty(required = true)
        @NotNull
        List<@Valid ExtractedFact> extractedFacts,

        @JsonProperty(required = true)
        @NotNull
        List<@Valid DisclosureRelation> relations
) {
}

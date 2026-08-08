package com.chaltteok.disclosure.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * 관계에서 변경된 필드. docs/data-model.md §6.
 */
public record ChangedField(
        @JsonProperty(required = true)
        @NotBlank
        String factKey,

        @JsonProperty(required = true)
        String beforeValue,

        @JsonProperty(required = true)
        String afterValue,

        @JsonProperty(required = true)
        String unit
) {
}

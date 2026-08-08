package com.chaltteok.disclosure.model;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * 공시 문서 공통 메타데이터. docs/data-model.md §3.
 */
public record DisclosureDocument(
        @JsonProperty(required = true)
        @NotBlank
        String docId,

        @JsonProperty(required = true)
        @NotBlank
        @Pattern(regexp = "\\d{8}", message = "corp_code는 정확히 8자리 숫자 문자열이어야 합니다")
        String corpCode,

        @JsonProperty(required = true)
        @NotBlank
        String corpName,

        @JsonProperty(required = true)
        @NotBlank
        String listedName,

        @JsonProperty(required = true)
        @NotBlank
        @Pattern(regexp = "\\d{6}", message = "stock_code는 정확히 6자리 숫자 문자열이어야 합니다")
        String stockCode,

        @JsonProperty(required = true)
        @NotBlank
        String industry,

        @JsonProperty(required = true)
        @NotBlank
        String sector,

        @JsonProperty(required = true)
        @NotNull
        DocGroup docGroup,

        @JsonProperty(required = true)
        String docSubtype,

        @JsonProperty(required = true)
        @NotBlank
        String reportNm,

        @JsonProperty(required = true)
        @NotBlank
        @Pattern(regexp = "\\d{14}", message = "rcept_no는 정확히 14자리 숫자 문자열이어야 합니다")
        String rceptNo,

        @JsonProperty(required = true)
        @NotNull
        LocalDate rceptDt,

        @JsonProperty(required = true)
        @NotBlank
        String flrNm,

        @JsonProperty(required = true)
        @NotNull
        Boolean isCorrection,

        @JsonProperty(required = true)
        Integer baseYear,

        @JsonProperty(required = true)
        @Min(1)
        @Max(12)
        Integer baseMonth,

        @JsonProperty(required = true)
        @NotBlank
        String sourcePath,

        @JsonProperty(required = true)
        @NotNull
        ManifestFileFormat manifestFileFormat,

        @JsonProperty(required = true)
        @NotNull
        @Min(1)
        Integer sourceFileCount
) {

    /**
     * {@code doc_group=periodic}이면 {@code base_year}와 {@code base_month}가 필수다.
     */
    @JsonIgnore
    @AssertTrue(message = "doc_group=periodic이면 base_year와 base_month가 필요합니다")
    public boolean isPeriodicBasePeriodPresent() {
        if (docGroup != DocGroup.PERIODIC) {
            return true;
        }
        return baseYear != null && baseMonth != null;
    }
}

package com.chaltteok.disclosure.model;

import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 검색·답변 근거 본문 조각. docs/data-model.md §4.
 *
 * <p>{@code content_type} 매핑 규칙:
 * <ul>
 *   <li>{@code paragraph} — XML·HTML 문단 또는 PDF 텍스트 블록</li>
 *   <li>{@code table} — 표·행·셀(행·열 제목과 값을 함께 보존). 거래소공시 key:value형 HTML 표 포함</li>
 *   <li>{@code list} — 실제 목록 또는 연속된 목록형 문단</li>
 * </ul>
 * {@code key_value}는 별도 content_type으로 두지 않는다.
 */
public record EvidenceChunk(
        @JsonProperty(required = true)
        @NotBlank
        String chunkId,

        @JsonProperty(required = true)
        @NotBlank
        String docId,

        @JsonProperty(required = true)
        @NotNull
        @Min(0)
        Integer sequence,

        @JsonProperty(required = true)
        @NotNull
        ContentType contentType,

        @JsonProperty(required = true)
        @NotBlank
        String text,

        @JsonProperty(required = true)
        @NotBlank
        String sourceFile,

        @JsonProperty(required = true)
        @NotNull
        SourceFormat sourceFormat,

        @JsonProperty(required = true)
        @Min(1)
        Integer pageNumber,

        @JsonProperty(required = true)
        String sectionTitle,

        @JsonProperty(required = true)
        String tableTitle,

        @JsonProperty(required = true)
        String rowLabel,

        @JsonProperty(required = true)
        String columnLabel
) {

    /**
     * {@code source_format=pdf}이면 {@code page_number} 필수(1 이상),
     * 그 외 형식이면 {@code page_number}는 null이어야 한다.
     */
    @JsonIgnore
    @AssertTrue(message = "source_format=pdf이면 page_number가 필요하고, 그 외에는 null이어야 합니다")
    public boolean isPageNumberConsistentWithSourceFormat() {
        if (sourceFormat == null) {
            return true;
        }
        if (sourceFormat == SourceFormat.PDF) {
            return pageNumber != null && pageNumber >= 1;
        }
        return pageNumber == null;
    }
}

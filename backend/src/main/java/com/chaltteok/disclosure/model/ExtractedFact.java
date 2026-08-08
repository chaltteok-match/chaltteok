package com.chaltteok.disclosure.model;

import com.chaltteok.disclosure.model.enums.PeriodBasis;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.model.enums.ValueType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 비교·계산·이력 판단용 구조화 사실. docs/data-model.md §5.
 *
 * <p>정규화 규칙(파서 측 책임, 이 모델은 계약 필드만 표현):
 * <ul>
 *   <li>{@code value_type=number}이면 {@code normalized_value}는 쉼표·단위 배수를 제거한 10진수 문자열</li>
 *   <li>금액은 KRW, 비율은 %, 주식 수는 share 단위로 통일</li>
 *   <li>{@code value_type=date}이면 {@code normalized_value}는 {@code YYYY-MM-DD}</li>
 *   <li>기간 값은 {@code period_start}/{@code period_end}, 시점 값은 {@code as_of_date}</li>
 *   <li>연결·별도 확인 불가면 {@code scope=unknown}</li>
 * </ul>
 */
public record ExtractedFact(
        @JsonProperty(required = true)
        @NotBlank
        String factId,

        @JsonProperty(required = true)
        @NotBlank
        String docId,

        @JsonProperty(required = true)
        @NotBlank
        String chunkId,

        @JsonProperty(required = true)
        @NotBlank
        String factKey,

        @JsonProperty(required = true)
        @NotBlank
        String label,

        @JsonProperty(required = true)
        @NotNull
        ValueType valueType,

        @JsonProperty(required = true)
        @NotBlank
        String rawValue,

        @JsonProperty(required = true)
        String rawUnit,

        @JsonProperty(required = true)
        String normalizedValue,

        @JsonProperty(required = true)
        String normalizedUnit,

        @JsonProperty(required = true)
        LocalDate periodStart,

        @JsonProperty(required = true)
        LocalDate periodEnd,

        @JsonProperty(required = true)
        LocalDate asOfDate,

        @JsonProperty(required = true)
        @NotNull
        PeriodBasis periodBasis,

        @JsonProperty(required = true)
        @NotNull
        Scope scope
) {
}

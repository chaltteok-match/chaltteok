package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 값을 정규화한 결과 상태. docs/data-model.md §5.
 *
 * <p>{@code normalized_value}가 비는 원인은 하나가 아니다. 상태를 남기지 않으면
 * 파서 결함과 원문의 정보 한계를 구분할 수 없고, 답변이 결함을 정보 한계로 위장하게 된다
 * (docs/data-model.md §9).
 *
 * <p>상태를 추가하면 기존 출력을 읽을 수 없으므로 {@code schema_version}을 올린다.
 */
public enum ExtractionStatus {

    /** 원문 값을 읽고 정규화까지 마쳤다. {@code normalized_value}가 있다. */
    OK,

    /** 값은 읽었지만 원문에 단위 표기가 없어 배수를 확정하지 못했다. 원문 값만 보존한다. */
    UNIT_MISSING;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ExtractionStatus fromJson(String value) {
        if (value == null) {
            return null;
        }
        return ExtractionStatus.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

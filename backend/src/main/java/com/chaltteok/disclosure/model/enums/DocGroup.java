package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 공시 유형 그룹. docs/data-model.md §3.
 */
public enum DocGroup {
    PERIODIC,
    MAJOR,
    EXCHANGE,
    HOLDING;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static DocGroup fromJson(String value) {
        if (value == null) {
            return null;
        }
        return DocGroup.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * ExtractedFact 값 유형. docs/data-model.md §5.
 */
public enum ValueType {
    NUMBER,
    TEXT,
    DATE,
    BOOLEAN;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ValueType fromJson(String value) {
        if (value == null) {
            return null;
        }
        return ValueType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

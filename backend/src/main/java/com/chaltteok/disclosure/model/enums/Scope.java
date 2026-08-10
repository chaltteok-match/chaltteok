package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 연결·별도 기준. docs/data-model.md §5.
 */
public enum Scope {
    CONSOLIDATED,
    SEPARATE,
    NOT_APPLICABLE,
    UNKNOWN;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Scope fromJson(String value) {
        if (value == null) {
            return null;
        }
        return Scope.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

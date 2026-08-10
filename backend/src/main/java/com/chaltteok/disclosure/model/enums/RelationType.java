package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 정정·변경·해지·후속 관계 유형. docs/data-model.md §6.
 */
public enum RelationType {
    CORRECTION,
    AMENDMENT,
    TERMINATION,
    FOLLOW_UP;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static RelationType fromJson(String value) {
        if (value == null) {
            return null;
        }
        return RelationType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

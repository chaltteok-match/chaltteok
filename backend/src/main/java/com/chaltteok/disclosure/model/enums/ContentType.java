package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 근거 원문 구조 유형. docs/data-model.md §4.
 */
public enum ContentType {
    PARAGRAPH,
    TABLE,
    LIST;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ContentType fromJson(String value) {
        if (value == null) {
            return null;
        }
        return ContentType.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

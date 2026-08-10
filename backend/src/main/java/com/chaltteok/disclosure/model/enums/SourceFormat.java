package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 실제 원문 내용 판별 형식. docs/data-model.md §2.5, §4.
 */
public enum SourceFormat {
    DART_XML,
    HTML,
    PDF;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static SourceFormat fromJson(String value) {
        if (value == null) {
            return null;
        }
        return SourceFormat.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

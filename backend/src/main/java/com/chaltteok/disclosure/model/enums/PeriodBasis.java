package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 기간·시점 기준. docs/data-model.md §5.
 */
public enum PeriodBasis {
    CUMULATIVE,
    QUARTER_ONLY,
    POINT_IN_TIME,
    NOT_APPLICABLE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static PeriodBasis fromJson(String value) {
        if (value == null) {
            return null;
        }
        return PeriodBasis.valueOf(value.toUpperCase(Locale.ROOT));
    }
}

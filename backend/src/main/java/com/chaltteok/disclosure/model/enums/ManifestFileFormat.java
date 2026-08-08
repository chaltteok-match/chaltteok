package com.chaltteok.disclosure.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * manifest의 file_format. docs/data-model.md §3.
 * JSON 값 {@code pdf+html}은 {@link #PDF_HTML}에 매핑한다.
 */
public enum ManifestFileFormat {
    XML("xml"),
    PDF_HTML("pdf+html");

    private final String jsonValue;

    ManifestFileFormat(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonValue
    public String toJson() {
        return jsonValue;
    }

    @JsonCreator
    public static ManifestFileFormat fromJson(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(format -> format.jsonValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown manifest_file_format: " + value));
    }
}

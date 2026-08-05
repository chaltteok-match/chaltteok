package com.chaltteok.answer.dto;

import java.time.LocalDate;

public record EvidenceDto(
        String disclosureId,
        String disclosureName,
        LocalDate disclosureDate,
        String excerpt
) {
}

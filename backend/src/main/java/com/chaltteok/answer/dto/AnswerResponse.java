package com.chaltteok.answer.dto;

import java.util.List;

public record AnswerResponse(
        String answer,
        List<EvidenceDto> evidences,
        String status
) {
}

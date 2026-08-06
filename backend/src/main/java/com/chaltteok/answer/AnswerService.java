package com.chaltteok.answer;

import com.chaltteok.answer.dto.AnswerResponse;
import com.chaltteok.answer.dto.EvidenceDto;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnswerService {

    public AnswerResponse answer(String question) {
        String normalizedQuestion = StringUtils.hasText(question)
                ? question.trim()
                : "(질문이 제공되지 않았습니다)";

        return new AnswerResponse(
                "임시 응답입니다. 공시 검색·계산·HyperCLOVA X 연동은 이후 단계에서 구현됩니다.",
                List.of(new EvidenceDto(
                        "skeleton-placeholder",
                        "백엔드 스켈레톤",
                        null,
                        "question=" + normalizedQuestion
                )),
                "skeleton"
        );
    }
}

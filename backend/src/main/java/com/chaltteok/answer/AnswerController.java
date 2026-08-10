package com.chaltteok.answer;

import com.chaltteok.answer.dto.AnswerSuccessResponse;
import com.chaltteok.answer.web.InvalidAnswerRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 평가용 {@code GET /answer}. docs/api-contract.md.
 * 검색·계산·LLM 호출은 하지 않고 {@link AnswerService} 결과를 DTO로만 변환한다.
 */
@RestController
@RequestMapping("/answer")
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @GetMapping
    public ResponseEntity<AnswerSuccessResponse> answer(
            @RequestParam(value = "question_id", required = false) String questionId,
            @RequestParam(value = "question", required = false) String question
    ) {
        validateRequest(questionId, question);
        AnswerResult result = answerService.answer(questionId, question);
        return ResponseEntity.ok(AnswerResponseMapper.toSuccessResponse(result));
    }

    /**
     * 공백 여부는 trim 기준으로 검사하고, 서비스·응답에는 요청 원문을 그대로 전달한다.
     */
    private void validateRequest(String questionId, String question) {
        if (!StringUtils.hasText(questionId)) {
            throw new InvalidAnswerRequestException(
                    null,
                    "question_id는 필수이며 공백일 수 없습니다."
            );
        }
        if (!StringUtils.hasText(question)) {
            throw new InvalidAnswerRequestException(
                    questionId,
                    "question은 필수이며 공백일 수 없습니다."
            );
        }
    }
}

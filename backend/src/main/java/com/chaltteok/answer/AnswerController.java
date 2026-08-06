package com.chaltteok.answer;

import com.chaltteok.answer.dto.AnswerResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/answer")
public class AnswerController {

    private final AnswerService answerService;

    public AnswerController(AnswerService answerService) {
        this.answerService = answerService;
    }

    @GetMapping
    public ResponseEntity<AnswerResponse> answer(
            @RequestParam(value = "question", required = false) String question
    ) {
        return ResponseEntity.ok(answerService.answer(question));
    }
}

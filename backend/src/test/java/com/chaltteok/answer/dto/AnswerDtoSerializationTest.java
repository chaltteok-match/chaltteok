package com.chaltteok.answer.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.chaltteok.config.JacksonConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.context.annotation.Import;

@JsonTest
@Import(JacksonConfig.class)
class AnswerDtoSerializationTest {

    @Autowired
    private JacksonTester<AnswerSuccessResponse> successTester;

    @Autowired
    private JacksonTester<AnswerErrorResponse> errorTester;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void successResponseUsesSnakeCaseFieldNames() throws Exception {
        AnswerSuccessResponse response = new AnswerSuccessResponse(
                "Q-001",
                "OO기업의 2025년 연결기준 매출액은 얼마인가?",
                "[근거 1]\\n공시명: 2025년 사업보고서",
                "기업·기간·연결기준 식별 → 검증",
                "OO기업의 2025년 연결기준 매출액은 12,345억원입니다."
        );

        JsonNode node = objectMapper.readTree(successTester.write(response).getJson());

        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "question_id",
                "question",
                "retrieved_context",
                "think_trace",
                "answer"
        );
        assertThat(node.get("question_id").asText()).isEqualTo("Q-001");
        assertThat(node.get("retrieved_context").asText())
                .isEqualTo("[근거 1]\\n공시명: 2025년 사업보고서");
        assertThat(node.get("think_trace").asText()).isEqualTo("기업·기간·연결기준 식별 → 검증");
        assertThat(node.has("questionId")).isFalse();
        assertThat(node.has("retrievedContext")).isFalse();
    }

    @Test
    void errorResponseUsesSnakeCaseAndAllowsNullQuestionId() throws Exception {
        AnswerErrorResponse response = AnswerErrorResponse.of(
                null,
                ErrorCode.INVALID_REQUEST,
                "question_id는 필수이며 공백일 수 없습니다."
        );

        JsonNode node = objectMapper.readTree(errorTester.write(response).getJson());

        assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "question_id",
                "error_code",
                "message",
                "retryable"
        );
        assertThat(node.get("question_id").isNull()).isTrue();
        assertThat(node.get("error_code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(node.get("message").asText())
                .isEqualTo("question_id는 필수이며 공백일 수 없습니다.");
        assertThat(node.get("retryable").asBoolean()).isFalse();
    }
}

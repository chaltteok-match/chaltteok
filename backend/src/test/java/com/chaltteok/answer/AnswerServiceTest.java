package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chaltteok.answer.web.ServiceUnavailableException;
import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxClientException;
import com.chaltteok.hcx.HcxErrorType;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

    @Mock
    private AnswerHandlerSelector handlerSelector;

    @Mock
    private HcxClient hcxClient;

    @Mock
    private AnswerHandler answerHandler;

    private AnswerService answerService;

    @BeforeEach
    void setUp() {
        answerService = new AnswerService(handlerSelector, new HcxPromptFactory(), hcxClient);
    }

    @Test
    void selectsSupportingHandler() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select("매출액 질문")).thenReturn(Optional.of(answerHandler));
        when(answerHandler.handle("Q-001", "매출액 질문")).thenReturn(handlerResultWithEvidence());
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("HCX 답변", "HCX-005", 1, 2, 3));

        answerService.answer("Q-001", "매출액 질문");

        verify(handlerSelector).select("매출액 질문");
        verify(answerHandler).handle("Q-001", "매출액 질문");
    }

    @Test
    void includesRetrievedContextInHcxPromptAndAnswerResult() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select(any())).thenReturn(Optional.of(answerHandler));
        when(answerHandler.handle(eq("Q-001"), eq("매출액은?"))).thenReturn(handlerResultWithEvidence());
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenReturn(new HcxResult("최종 답변입니다.", "HCX-005", 10, 20, 30));

        AnswerResult result = answerService.answer("Q-001", "매출액은?");

        ArgumentCaptor<HcxPrompt> promptCaptor = ArgumentCaptor.forClass(HcxPrompt.class);
        verify(hcxClient).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().userPrompt())
                .contains("공시명: 2025년 사업보고서")
                .contains("공시일: 2026-03-20")
                .contains("기업명: 예시전자")
                .contains("연결 매출액은 12,345억원");

        assertThat(result.retrievedContext())
                .contains("[근거 1]")
                .contains("공시명: 2025년 사업보고서")
                .contains("근거: 연결 매출액은 12,345억원");
        assertThat(result.thinkTrace()).isEqualTo("기업·기간 식별 → 사업보고서 검색");
        assertThat(result.answer()).isEqualTo("최종 답변입니다.");
        assertThat(result.questionId()).isEqualTo("Q-001");
        assertThat(result.question()).isEqualTo("매출액은?");
    }

    @Test
    void doesNotCallHcxWhenEvidenceMissing() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select(any())).thenReturn(Optional.of(answerHandler));
        when(answerHandler.handle(any(), any())).thenReturn(
                AnswerHandlerResult.unavailable("검색 수행", "관련 공시를 찾지 못함"));

        AnswerResult result = answerService.answer("Q-002", "없는 질문");

        verify(hcxClient, never()).generate(any());
        assertThat(result.retrievedContext()).isEmpty();
        assertThat(result.thinkTrace()).contains("관련 공시를 찾지 못함");
        assertThat(result.answer()).isEqualTo(AnswerService.ANSWER_UNAVAILABLE);
    }

    @Test
    void returnsUnavailableWhenNoHandlerRegistered() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(false);

        AnswerResult result = answerService.answer("Q-003", "아무 질문");

        verify(handlerSelector, never()).select(any());
        verify(hcxClient, never()).generate(any());
        assertThat(result.answer()).isEqualTo(AnswerService.ANSWER_UNAVAILABLE);
        assertThat(result.thinkTrace()).contains("등록된 AnswerHandler");
    }

    @Test
    void returnsUnavailableWhenNoHandlerSupportsQuestion() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select(any())).thenReturn(Optional.empty());

        AnswerResult result = answerService.answer("Q-004", "지원 안 되는 질문");

        verify(hcxClient, never()).generate(any());
        assertThat(result.answer()).isEqualTo(AnswerService.ANSWER_UNAVAILABLE);
        assertThat(result.thinkTrace()).contains("지원하는 AnswerHandler");
    }

    @Test
    void mapsHcxFailureToServiceUnavailable() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select(any())).thenReturn(Optional.of(answerHandler));
        when(answerHandler.handle(any(), any())).thenReturn(handlerResultWithEvidence());
        when(hcxClient.generate(any(HcxPrompt.class)))
                .thenThrow(new HcxClientException(HcxErrorType.UPSTREAM, "upstream down"));

        assertThatThrownBy(() -> answerService.answer("Q-503", "매출액은?"))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting(ex -> ((ServiceUnavailableException) ex).questionId())
                .isEqualTo("Q-503");
    }

    @Test
    void mapsDataAccessFailureToServiceUnavailable() {
        when(handlerSelector.hasRegisteredHandlers()).thenReturn(true);
        when(handlerSelector.select(any())).thenReturn(Optional.of(answerHandler));
        when(answerHandler.handle(any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> answerService.answer("Q-503-DB", "매출액은?"))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting(ex -> ((ServiceUnavailableException) ex).questionId())
                .isEqualTo("Q-503-DB");
        verify(hcxClient, never()).generate(any());
    }

    private static AnswerHandlerResult handlerResultWithEvidence() {
        return AnswerHandlerResult.of(
                List.of(new RetrievedEvidence(
                        "2025년 사업보고서",
                        LocalDate.of(2026, 3, 20),
                        "예시전자",
                        "연결 매출액은 12,345억원"
                )),
                "기업·기간 식별 → 사업보고서 검색"
        );
    }
}

package com.chaltteok.answer;

import com.chaltteok.answer.web.ServiceUnavailableException;
import com.chaltteok.hcx.HcxClient;
import com.chaltteok.hcx.HcxClientException;
import com.chaltteok.hcx.HcxPrompt;
import com.chaltteok.hcx.HcxResult;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 질문 수신 → Handler 선택 → 근거 조립 → HCX 호출 → {@link AnswerResult} 생성.
 * 검색·LLM 로직은 Controller에 두지 않는다.
 */
@Service
public class AnswerService {

    static final String ANSWER_UNAVAILABLE = "제공된 공시 데이터에서는 확인할 수 없습니다.";
    private static final String NO_HANDLER_TRACE = "등록된 AnswerHandler가 없어 질문을 처리할 수 없습니다";
    private static final String NO_SUPPORTING_HANDLER_TRACE = "질문을 지원하는 AnswerHandler가 없습니다";
    private static final String SERVICE_UNAVAILABLE_MESSAGE =
            "일시적으로 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    private final AnswerHandlerSelector handlerSelector;
    private final HcxPromptFactory hcxPromptFactory;
    private final HcxClient hcxClient;

    public AnswerService(
            AnswerHandlerSelector handlerSelector,
            HcxPromptFactory hcxPromptFactory,
            HcxClient hcxClient
    ) {
        this.handlerSelector = handlerSelector;
        this.hcxPromptFactory = hcxPromptFactory;
        this.hcxClient = hcxClient;
    }

    public AnswerResult answer(String questionId, String question) {
        try {
            if (!handlerSelector.hasRegisteredHandlers()) {
                return unavailable(questionId, question, "", NO_HANDLER_TRACE);
            }

            return handlerSelector.select(question)
                    .map(handler -> processWithHandler(questionId, question, handler))
                    .orElseGet(() -> unavailable(questionId, question, "", NO_SUPPORTING_HANDLER_TRACE));
        } catch (ServiceUnavailableException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw new ServiceUnavailableException(questionId, SERVICE_UNAVAILABLE_MESSAGE, ex);
        } catch (HcxClientException ex) {
            throw new ServiceUnavailableException(questionId, SERVICE_UNAVAILABLE_MESSAGE, ex);
        }
    }

    private AnswerResult processWithHandler(String questionId, String question, AnswerHandler handler) {
        AnswerHandlerResult handlerResult;
        try {
            handlerResult = handler.handle(questionId, question);
        } catch (DataAccessException ex) {
            throw new ServiceUnavailableException(questionId, SERVICE_UNAVAILABLE_MESSAGE, ex);
        }

        String retrievedContext = RetrievedContextFormatter.format(handlerResult.evidences());
        String thinkTrace = resolveThinkTrace(handlerResult);

        if (!handlerResult.isAnswerable()) {
            return unavailable(questionId, question, retrievedContext, thinkTrace);
        }

        HcxPrompt prompt = hcxPromptFactory.create(question, retrievedContext, thinkTrace);
        HcxResult hcxResult;
        try {
            hcxResult = hcxClient.generate(prompt);
        } catch (HcxClientException ex) {
            throw new ServiceUnavailableException(questionId, SERVICE_UNAVAILABLE_MESSAGE, ex);
        }

        return new AnswerResult(
                questionId,
                question,
                retrievedContext,
                thinkTrace,
                hcxResult.content()
        );
    }

    private static String resolveThinkTrace(AnswerHandlerResult handlerResult) {
        if (handlerResult.unavailableReason() != null && !handlerResult.unavailableReason().isBlank()) {
            if (handlerResult.processSummary() == null || handlerResult.processSummary().isBlank()) {
                return handlerResult.unavailableReason();
            }
            return handlerResult.processSummary() + " → " + handlerResult.unavailableReason();
        }
        return handlerResult.processSummary();
    }

    private static AnswerResult unavailable(
            String questionId,
            String question,
            String retrievedContext,
            String thinkTrace
    ) {
        return new AnswerResult(questionId, question, retrievedContext, thinkTrace, ANSWER_UNAVAILABLE);
    }
}

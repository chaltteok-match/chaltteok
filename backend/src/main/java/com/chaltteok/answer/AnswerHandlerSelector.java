package com.chaltteok.answer;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 등록된 {@link AnswerHandler} 중 질문을 처리할 Handler를 고른다.
 * 구현체가 없으면 empty를 반환한다.
 */
@Component
public class AnswerHandlerSelector {

    private final List<AnswerHandler> handlers;

    public AnswerHandlerSelector(List<AnswerHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public Optional<AnswerHandler> select(String question) {
        return handlers.stream()
                .filter(handler -> handler.supports(question))
                .findFirst();
    }

    public boolean hasRegisteredHandlers() {
        return !handlers.isEmpty();
    }
}

package com.chaltteok.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerHandlerSelectorTest {

    @Mock
    private AnswerHandler first;

    @Mock
    private AnswerHandler second;

    @Test
    void selectsFirstSupportingHandler() {
        when(first.supports("질문")).thenReturn(false);
        when(second.supports("질문")).thenReturn(true);

        AnswerHandlerSelector selector = new AnswerHandlerSelector(List.of(first, second));

        assertThat(selector.select("질문")).contains(second);
        assertThat(selector.hasRegisteredHandlers()).isTrue();
    }

    @Test
    void returnsEmptyWhenNoHandlerSupports() {
        when(first.supports("질문")).thenReturn(false);

        AnswerHandlerSelector selector = new AnswerHandlerSelector(List.of(first));

        assertThat(selector.select("질문")).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoHandlersRegistered() {
        AnswerHandlerSelector selector = new AnswerHandlerSelector(List.of());

        assertThat(selector.hasRegisteredHandlers()).isFalse();
        assertThat(selector.select("질문")).isEmpty();
    }
}

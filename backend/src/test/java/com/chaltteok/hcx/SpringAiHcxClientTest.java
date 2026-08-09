package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class SpringAiHcxClientTest {

    @Mock
    private ChatModel chatModel;

    private SpringAiHcxClient client;

    @BeforeEach
    void setUp() {
        client = new SpringAiHcxClient(chatModel);
    }

    @Test
    void returnsGeneratedContentAndUsage() {
        var metadata = ChatResponseMetadata.builder()
                .model("HCX-005")
                .usage(new DefaultUsage(10, 20, 30))
                .build();
        var response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("근거 기반 답변"))),
                metadata);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        var result = client.generate(new HcxPrompt("근거만 사용하세요.", "매출액은?"));

        assertThat(result.content()).isEqualTo("근거 기반 답변");
        assertThat(result.model()).isEqualTo("HCX-005");
        assertThat(result.promptTokens()).isEqualTo(10);
        assertThat(result.completionTokens()).isEqualTo(20);
        assertThat(result.totalTokens()).isEqualTo(30);

        var promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getInstructions())
                .hasSize(2)
                .element(0).isInstanceOf(SystemMessage.class);
        assertThat(promptCaptor.getValue().getInstructions())
                .element(1).isInstanceOf(UserMessage.class);
    }

    @Test
    void rejectsResponseWithoutContent() {
        var response = new ChatResponse(
                List.of(new Generation(new AssistantMessage(" "))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        assertThatThrownBy(() -> client.generate(new HcxPrompt("지시문", "질문")))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(HcxErrorType.INVALID_RESPONSE);
    }

    @Test
    void convertsTimeoutToClientException() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new ResourceAccessException("read timed out"));

        assertThatThrownBy(() -> client.generate(new HcxPrompt("지시문", "질문")))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(HcxErrorType.TIMEOUT);
    }

    @Test
    void convertsSpringAiFailureToUpstreamException() {
        when(chatModel.call(any(Prompt.class)))
                .thenThrow(new TransientAiException("service unavailable"));

        assertThatThrownBy(() -> client.generate(new HcxPrompt("지시문", "질문")))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(HcxErrorType.UPSTREAM);
    }
}

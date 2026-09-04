package com.chaltteok.hcx;

import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

final class SpringAiHcxClient implements HcxClient {

    private final ChatModel chatModel;

    SpringAiHcxClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public HcxResult generate(HcxPrompt prompt) {
        try {
            var response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(prompt.systemPrompt()),
                    new UserMessage(prompt.userPrompt()))));

            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    || response.getResult().getOutput().getText() == null
                    || response.getResult().getOutput().getText().isBlank()) {
                // 여기까지 왔다는 것은 응답 본문을 이미 읽었다는 뜻이다. 모델이 빈 텍스트를
                // 준 경우만 이 타입이다. 본문을 못 읽으면 아래 catch로 간다.
                throw new HcxClientException(
                        HcxErrorType.INVALID_RESPONSE,
                        "HyperCLOVA X 응답에 답변 텍스트가 없습니다.");
            }

            var metadata = response.getMetadata();
            Usage usage = metadata == null ? null : metadata.getUsage();

            return new HcxResult(
                    response.getResult().getOutput().getText(),
                    metadata == null ? null : metadata.getModel(),
                    usage == null ? null : usage.getPromptTokens(),
                    usage == null ? null : usage.getCompletionTokens(),
                    usage == null ? null : usage.getTotalTokens());
        } catch (HcxClientException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new HcxClientException(
                    HcxErrorType.TIMEOUT,
                    "HyperCLOVA X 호출 시간이 초과되었습니다.",
                    exception);
        } catch (TransientAiException | NonTransientAiException exception) {
            throw new HcxClientException(
                    HcxErrorType.UPSTREAM,
                    "HyperCLOVA X 호출 중 외부 API 오류가 발생했습니다.",
                    exception);
        }
    }
}

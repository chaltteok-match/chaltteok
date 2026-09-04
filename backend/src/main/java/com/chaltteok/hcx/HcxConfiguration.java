package com.chaltteok.hcx;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HcxProperties.class)
class HcxConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "hcx", name = "enabled", havingValue = "true")
    HcxClient hcxClient(HcxProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new HcxClientException(
                    HcxErrorType.CONFIGURATION,
                    "HCX_ENABLED=true일 때 HCX_API_KEY가 필요합니다.");
        }

        var timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        var openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .completionsPath(properties.getCompletionsPath())
                .apiKey(properties.getApiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .responseErrorHandler(new HcxResponseErrorHandler())
                .build();

        var options = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                .build();

        // Spring AI 안쪽 재시도는 끈다. 재시도는 RetryingHcxClient가 한 곳에서 맡는다.
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();

        return new RetryingHcxClient(
                new SpringAiHcxClient(chatModel),
                properties.getRetryMaxAttempts(),
                Duration.ofMillis(properties.getRetryInitialIntervalMillis()),
                properties.getRetryMultiplier(),
                Duration.ofMillis(properties.getRetryMaxIntervalMillis()),
                Duration.ofSeconds(properties.getRetryMaxElapsedSeconds()),
                duration -> Thread.sleep(duration.toMillis()));
    }

    @Bean
    @ConditionalOnMissingBean(HcxClient.class)
    HcxClient disabledHcxClient() {
        return prompt -> {
            throw new HcxClientException(
                    HcxErrorType.CONFIGURATION,
                    "HyperCLOVA X 클라이언트가 비활성화되어 있습니다. HCX_ENABLED=true로 설정하세요.");
        };
    }
}

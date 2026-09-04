package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HcxConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HcxConfiguration.class);

    @Test
    void providesDisabledClientWithoutApiKey() {
        contextRunner
                .withPropertyValues("hcx.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(HcxClient.class);
                    assertThat(context.getBean(HcxClient.class))
                            .isNotInstanceOf(SpringAiHcxClient.class);
                });
    }

    @Test
    void providesSpringAiClientWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "hcx.enabled=true",
                        "hcx.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(HcxClient.class);
                    // 재시도 데코레이터로 감싸 내보낸다.
                    assertThat(context.getBean(HcxClient.class))
                            .isInstanceOf(RetryingHcxClient.class);
                });
    }

    @Test
    void retrySettingsComeFromProperties() {
        contextRunner
                .withPropertyValues(
                        "hcx.enabled=true",
                        "hcx.api-key=test-key",
                        "hcx.retry-max-attempts=5",
                        "hcx.retry-initial-interval-millis=250",
                        "hcx.retry-multiplier=1.5",
                        "hcx.retry-max-interval-millis=4000",
                        "hcx.retry-max-elapsed-seconds=60")
                .run(context -> {
                    HcxProperties properties = context.getBean(HcxProperties.class);
                    assertThat(properties.getRetryMaxAttempts()).isEqualTo(5);
                    assertThat(properties.getRetryInitialIntervalMillis()).isEqualTo(250);
                    assertThat(properties.getRetryMultiplier()).isEqualTo(1.5);
                    assertThat(properties.getRetryMaxIntervalMillis()).isEqualTo(4000);
                    assertThat(properties.getRetryMaxElapsedSeconds()).isEqualTo(60);
                });
    }

    @Test
    void retryDefaultsStayInsideTheEvaluationTimeout() {
        contextRunner
                .withPropertyValues("hcx.enabled=true", "hcx.api-key=test-key")
                .run(context -> {
                    HcxProperties properties = context.getBean(HcxProperties.class);
                    // 시도마다 호출 타임아웃을 다 쓰고 백오프까지 더해도 평가 타임아웃 300초 안이어야 한다.
                    long worstCalls = (long) properties.getRetryMaxAttempts()
                            * properties.getTimeoutSeconds();
                    assertThat(worstCalls + properties.getRetryMaxElapsedSeconds())
                            .isLessThan(300);
                });
    }

    @Test
    void failsFastWhenEnabledWithoutApiKey() {
        contextRunner
                .withPropertyValues(
                        "hcx.enabled=true",
                        "hcx.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(HcxClientException.class)
                            .rootCause()
                            .extracting(cause -> ((HcxClientException) cause).getErrorType())
                            .isEqualTo(HcxErrorType.CONFIGURATION);
                });
    }
}

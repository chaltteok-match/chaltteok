package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void applicationYamlKeepsTheMeasuredMaxTokensDefault() throws Exception {
        // 기본값이 자바와 application.yml 두 곳에 있고, 실제로 뜨는 값은 yml이 정한다.
        // 어긋나도 스프링 컨텍스트로는 잡히지 않는다. src/test/resources/application.yml이
        // 운영 설정을 가려서 테스트는 운영 yml을 아예 읽지 못하기 때문이다.
        // 실측해서 정한 값이 조용히 묻히지 않도록 파일을 직접 읽어 둘을 맞춰 둔다.
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).contains(
                "max-tokens: ${HCX_MAX_TOKENS:" + new HcxProperties().getMaxTokens() + "}");
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

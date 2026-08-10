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
                    assertThat(context.getBean(HcxClient.class))
                            .isInstanceOf(SpringAiHcxClient.class);
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

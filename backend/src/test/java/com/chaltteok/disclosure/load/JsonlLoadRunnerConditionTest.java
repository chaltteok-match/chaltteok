package com.chaltteok.disclosure.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chaltteok.disclosure.persistence.DisclosurePersistenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 적재 러너는 {@code load} 프로필에서만 등록되어야 한다.
 * 평가 API로 뜨는 평상시 실행에는 영향을 주지 않아야 한다.
 */
class JsonlLoadRunnerConditionTest {

    @Test
    void doesNotRegisterRunnerWithoutLoadProfile() {
        new ApplicationContextRunner()
                .withPropertyValues("chaltteok.disclosure.persistence.enabled=true")
                .withUserConfiguration(StubDependencies.class, LoadComponents.class)
                .run(context -> assertThat(context).doesNotHaveBean(JsonlLoadRunner.class));
    }

    @Test
    void registersRunnerWithLoadProfile() {
        new ApplicationContextRunner()
                .withPropertyValues(
                        "spring.profiles.active=load",
                        // ApplicationContextRunner는 application-load.yml을 읽지 않으므로
                        // 해당 프로필이 켜 주는 값을 직접 준다.
                        "chaltteok.disclosure.persistence.enabled=true")
                .withUserConfiguration(StubDependencies.class, LoadComponents.class)
                .run(context -> assertThat(context).hasSingleBean(JsonlLoadRunner.class));
    }

    @Test
    void doesNotRegisterLoaderWhenPersistenceDisabled() {
        new ApplicationContextRunner()
                .withPropertyValues("chaltteok.disclosure.persistence.enabled=false")
                .withUserConfiguration(StubDependencies.class, LoadComponents.class)
                .run(context -> assertThat(context).doesNotHaveBean(JsonlDisclosureLoader.class));
    }

    @Test
    void failsWhenLoadPathMissing() {
        JsonlLoadProperties properties = new JsonlLoadProperties();
        JsonlLoadRunner runner = new JsonlLoadRunner(mock(JsonlDisclosureLoader.class), properties);

        assertThat(catchThrowable(() -> runner.run(null)))
                .isInstanceOf(JsonlLoadException.class)
                .hasMessageContaining("chaltteok.load.path");
    }

    private static Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import({
            JsonlDisclosureLoader.class, JsonlLoadRunner.class
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties(JsonlLoadProperties.class)
    static class LoadComponents {
    }

    @Configuration(proxyBeanMethods = false)
    static class StubDependencies {

        @Bean
        DisclosurePersistenceService persistenceService() {
            return mock(DisclosurePersistenceService.class);
        }

        @Bean
        Validator validator() {
            return mock(Validator.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

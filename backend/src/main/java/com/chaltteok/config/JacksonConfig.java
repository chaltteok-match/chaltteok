package com.chaltteok.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 API·전처리 JSON 모두 snake_case 필드명을 사용한다.
 * docs/api-contract.md §2, docs/data-model.md §2.2.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // 필수 creator 프로퍼티 키가 JSON에 없으면 역직렬화 실패
                .featuresToEnable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
    }
}

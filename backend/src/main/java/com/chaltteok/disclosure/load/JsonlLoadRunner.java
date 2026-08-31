package com.chaltteok.disclosure.load;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@code load} 프로필에서만 동작하는 적재 진입점.
 *
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=local,load \
 *     --chaltteok.load.path=/path/to/result.jsonl'
 * </pre>
 *
 * <p>{@code load} 프로필은 웹 서버를 띄우지 않으므로 적재가 끝나면 애플리케이션이 종료된다.
 * 실패가 한 건이라도 있으면 예외를 던져 종료 코드를 0이 아닌 값으로 만든다.
 */
@Component
@Profile("load")
@EnableConfigurationProperties(JsonlLoadProperties.class)
public class JsonlLoadRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonlLoadRunner.class);

    private final JsonlDisclosureLoader loader;
    private final JsonlLoadProperties properties;

    public JsonlLoadRunner(JsonlDisclosureLoader loader, JsonlLoadProperties properties) {
        this.loader = loader;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.getPath())) {
            throw new JsonlLoadException(
                    "적재할 파일 경로가 없습니다. --chaltteok.load.path=<JSONL 경로>를 지정하세요.");
        }

        Path path = Path.of(properties.getPath());
        log.info("JSONL 적재를 시작합니다: {}", path);
        LoadSummary summary = loader.load(path);

        if (summary.hasFailure()) {
            throw new JsonlLoadException(
                    "적재에 실패한 줄이 있어 성공으로 종료하지 않습니다." + System.lineSeparator()
                            + summary.describe());
        }
        log.info("JSONL 적재를 마쳤습니다.");
    }
}

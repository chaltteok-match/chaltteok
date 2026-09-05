package com.chaltteok;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 평가 서버용 설정이 저장소만으로 뜰 수 있는지, 비밀이 섞이지 않았는지 본다.
 *
 * <p>스프링 컨텍스트로는 확인할 수 없다. 테스트 클래스패스의 {@code application.yml}이
 * 운영 설정을 가리기 때문이다. 파일을 직접 읽는다.
 */
class ServerProfileConfigurationTest {

    private static final Path SERVER_PROFILE =
            Path.of("src/main/resources/application-server.yml");

    @Test
    void receivesDatabaseSettingsFromTheEnvironment() throws IOException {
        String yaml = Files.readString(SERVER_PROFILE);

        // 기본값을 두면 값을 안 넘겨도 조용히 뜨고, 엉뚱한 DB를 본다.
        assertThat(yaml)
                .contains("url: ${DB_URL}")
                .contains("username: ${DB_USERNAME}")
                .contains("password: ${DB_PASSWORD}");
    }

    @Test
    void keepsSecretsOutOfTheRepository() throws IOException {
        String yaml = Files.readString(SERVER_PROFILE);

        for (String line : yaml.lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                continue;
            }
            // `password: ${DB_PASSWORD}`는 되고, 값을 직접 적은 줄은 안 된다.
            if (trimmed.startsWith("password:") || trimmed.startsWith("api-key:")) {
                assertThat(trimmed).contains("${");
            }
        }
    }

    @Test
    void doesNotChangeTheSchemaWhileServing() throws IOException {
        // 평가 중에 Entity 기준으로 스키마를 고치면 적재된 데이터가 위험하다.
        assertThat(Files.readString(SERVER_PROFILE)).contains("ddl-auto: none");
    }

    @Test
    void sharedConfigurationCarriesNoDatabaseCredentials() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yaml).doesNotContain("datasource");
    }
}

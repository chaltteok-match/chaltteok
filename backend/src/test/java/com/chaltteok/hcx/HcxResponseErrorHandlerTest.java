package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

class HcxResponseErrorHandlerTest {

    private final HcxResponseErrorHandler handler = new HcxResponseErrorHandler();

    @Test
    void mapsUnauthorizedToAuthenticationError() {
        assertErrorType(HttpStatus.UNAUTHORIZED, HcxErrorType.AUTHENTICATION);
    }

    @Test
    void mapsForbiddenToAuthenticationError() {
        assertErrorType(HttpStatus.FORBIDDEN, HcxErrorType.AUTHENTICATION);
    }

    @Test
    void mapsTooManyRequestsToRateLimitError() {
        assertErrorType(HttpStatus.TOO_MANY_REQUESTS, HcxErrorType.RATE_LIMIT);
    }

    @Test
    void mapsServerErrorToUpstreamError() {
        assertErrorType(HttpStatus.SERVICE_UNAVAILABLE, HcxErrorType.UPSTREAM);
    }

    @Test
    void readsRetryAfterSeconds() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "7");

        assertThat(HcxResponseErrorHandler.retryAfter(headers)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void readsRetryAfterHttpDate() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER,
                ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(30)
                        .format(DateTimeFormatter.RFC_1123_DATE_TIME));

        assertThat(HcxResponseErrorHandler.retryAfter(headers))
                .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(31));
    }

    @Test
    void ignoresUnreadableRetryAfter() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "곧");

        assertThat(HcxResponseErrorHandler.retryAfter(headers)).isNull();
    }

    @Test
    void hasNoRetryAfterWhenHeaderIsAbsent() {
        assertThat(HcxResponseErrorHandler.retryAfter(new HttpHeaders())).isNull();
    }

    @Test
    void carriesRetryAfterOnRateLimit() {
        var response = new MockClientHttpResponse(new byte[0], HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "3");

        assertThatThrownBy(() -> handler.handleError(URI.create("https://example.test"),
                        HttpMethod.POST, response))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getRetryAfter())
                .isEqualTo(Duration.ofSeconds(3));
    }

    private void assertErrorType(HttpStatus status, HcxErrorType expectedErrorType) {
        var response = new MockClientHttpResponse(new byte[0], status);

        assertThatThrownBy(() -> handler.handleError(
                        URI.create("https://clovastudio.stream.ntruss.com/v1/openai/chat/completions"),
                        HttpMethod.POST,
                        response))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(expectedErrorType);
    }
}

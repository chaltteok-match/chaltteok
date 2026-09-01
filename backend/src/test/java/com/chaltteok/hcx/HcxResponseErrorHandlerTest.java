package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
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

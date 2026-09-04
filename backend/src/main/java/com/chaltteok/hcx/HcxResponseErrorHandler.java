package com.chaltteok.hcx;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

final class HcxResponseErrorHandler implements ResponseErrorHandler {

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        var status = response.getStatusCode();
        var message = "HyperCLOVA X 호출에 실패했습니다. status=" + status.value();
        var retryAfter = retryAfter(response.getHeaders());

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            throw new HcxClientException(HcxErrorType.AUTHENTICATION, message);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            throw new HcxClientException(HcxErrorType.RATE_LIMIT, message, retryAfter, null);
        }
        // 429가 아닌 4xx는 우리 요청이 잘못된 것이다. 프롬프트가 그대로인 채 다시 걸면 같은 답이 온다.
        if (status.is4xxClientError()) {
            throw new HcxClientException(HcxErrorType.BAD_REQUEST, message);
        }
        throw new HcxClientException(HcxErrorType.UPSTREAM, message, retryAfter, null);
    }

    /**
     * {@code Retry-After}는 초 단위 정수이거나 HTTP 날짜다(RFC 9110 §10.2.3).
     * 읽지 못하면 우리 백오프를 쓰도록 {@code null}을 돌려준다.
     */
    static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            // 정수가 아니면 HTTP 날짜다.
        }
        try {
            Duration until = Duration.between(
                    ZonedDateTime.now(),
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}

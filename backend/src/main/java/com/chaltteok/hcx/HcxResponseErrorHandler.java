package com.chaltteok.hcx;

import java.io.IOException;
import java.net.URI;
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

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            throw new HcxClientException(HcxErrorType.AUTHENTICATION, message);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            throw new HcxClientException(HcxErrorType.RATE_LIMIT, message);
        }
        throw new HcxClientException(HcxErrorType.UPSTREAM, message);
    }
}

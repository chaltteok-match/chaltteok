package com.chaltteok.answer.web;

import com.chaltteok.answer.dto.AnswerErrorResponse;
import com.chaltteok.answer.dto.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 평가 API 오류 응답 조립. docs/api-contract.md §7.
 * 500/503은 이후 의존 서비스 연동 시 동일 {@link AnswerErrorResponse} 형식으로 확장한다.
 */
@RestControllerAdvice
public class AnswerExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidAnswerRequestException.class)
    public ResponseEntity<AnswerErrorResponse> handleInvalidRequest(InvalidAnswerRequestException ex) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity
                .status(code.httpStatus())
                .body(AnswerErrorResponse.of(ex.questionId(), code, ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity
                .status(code.httpStatus())
                .headers(headers)
                .body(AnswerErrorResponse.of(
                        null,
                        code,
                        "허용되지 않은 HTTP 메서드입니다. GET만 지원합니다."
                ));
    }
}

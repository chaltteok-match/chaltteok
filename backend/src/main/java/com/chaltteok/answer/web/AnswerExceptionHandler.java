package com.chaltteok.answer.web;

import com.chaltteok.answer.dto.AnswerErrorResponse;
import com.chaltteok.answer.dto.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 평가 API 오류 응답 조립. docs/api-contract.md §7.
 * 503({@link ErrorCode#SERVICE_UNAVAILABLE})은 DB·HyperCLOVA X 연동 시 동일
 * {@link AnswerErrorResponse} 형식으로 확장한다.
 */
@RestControllerAdvice
public class AnswerExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AnswerExceptionHandler.class);

    private static final String INTERNAL_ERROR_MESSAGE = "서버 내부 오류가 발생했습니다.";

    @ExceptionHandler(InvalidAnswerRequestException.class)
    public ResponseEntity<AnswerErrorResponse> handleInvalidRequest(InvalidAnswerRequestException ex) {
        ErrorCode code = ErrorCode.INVALID_REQUEST;
        return ResponseEntity
                .status(code.httpStatus())
                .body(AnswerErrorResponse.of(ex.questionId(), code, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<AnswerErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing request", ex);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(code.httpStatus())
                .body(AnswerErrorResponse.of(resolveQuestionId(request), code, INTERNAL_ERROR_MESSAGE));
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
                        resolveQuestionId(request),
                        code,
                        "허용되지 않은 HTTP 메서드입니다. GET만 지원합니다."
                ));
    }

    private static String resolveQuestionId(WebRequest request) {
        String questionId = request.getParameter("question_id");
        return StringUtils.hasText(questionId) ? questionId : null;
    }
}

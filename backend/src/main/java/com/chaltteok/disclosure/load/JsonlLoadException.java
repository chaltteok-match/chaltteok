package com.chaltteok.disclosure.load;

/**
 * 적재를 성공으로 끝낼 수 없는 상태. 종료 코드를 0이 아닌 값으로 만든다.
 */
public class JsonlLoadException extends RuntimeException {

    public JsonlLoadException(String message) {
        super(message);
    }

    public JsonlLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

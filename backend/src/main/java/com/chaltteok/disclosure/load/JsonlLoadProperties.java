package com.chaltteok.disclosure.load;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JSONL 적재 실행 설정. {@code load} 프로필에서만 사용한다.
 */
@ConfigurationProperties(prefix = "chaltteok.load")
public class JsonlLoadProperties {

    /** 적재할 JSONL 파일 경로. 1줄 = PreprocessResult 1건. */
    private String path;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}

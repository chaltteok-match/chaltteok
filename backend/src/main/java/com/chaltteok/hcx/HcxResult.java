package com.chaltteok.hcx;

/** HyperCLOVA X가 생성한 답변과 사용량 정보입니다. */
public record HcxResult(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}

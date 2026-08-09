package com.chaltteok.hcx;

/** HyperCLOVA X에 전달할 시스템 지시문과 사용자 메시지입니다. */
public record HcxPrompt(String systemPrompt, String userPrompt) {

    public HcxPrompt {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt는 비어 있을 수 없습니다.");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt는 비어 있을 수 없습니다.");
        }
    }
}

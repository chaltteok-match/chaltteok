package com.chaltteok.hcx;

/** HyperCLOVA X 호출을 외부 서비스 계층에서 분리하는 공통 인터페이스입니다. */
public interface HcxClient {

    HcxResult generate(HcxPrompt prompt);
}

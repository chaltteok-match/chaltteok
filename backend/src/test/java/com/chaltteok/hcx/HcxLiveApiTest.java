package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "hcx.enabled=true")
@EnabledIfEnvironmentVariable(named = "HCX_LIVE_TEST", matches = "true")
class HcxLiveApiTest {

    @Autowired
    private HcxClient hcxClient;

    @Test
    void callsHyperClovaXWithRealCredentials() {
        var result = hcxClient.generate(new HcxPrompt(
                "질문에 한 문장으로 답하세요.",
                "대한민국의 수도는 어디인가요?"));

        assertThat(result.content()).isNotBlank();
    }
}

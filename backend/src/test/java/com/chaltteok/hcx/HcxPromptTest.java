package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class HcxPromptTest {

    @Test
    void rejectsBlankSystemPrompt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HcxPrompt(" ", "질문"));
    }

    @Test
    void rejectsBlankUserPrompt() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new HcxPrompt("지시문", " "));
    }
}

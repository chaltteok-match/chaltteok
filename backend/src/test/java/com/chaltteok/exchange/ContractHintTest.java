package com.chaltteok.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractHintTest {

    private static Map<String, List<String>> candidates(String... pairs) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put("doc" + (i / 2), List.of(pairs[i], pairs[i + 1]));
        }
        return values;
    }

    @Test
    void picksTheCandidateTheQuestionNames() {
        // 한화오션 2023-12-22. 방위사업청과 두 건, 오세아니아 선주와 한 건이다.
        Map<String, List<String>> values = candidates(
                "방위사업청", "수상함 1척",
                "오세아니아 지역 선주", "FCS(필드제어설비) 1기",
                "방위사업청", "잠수함 1척");

        assertThat(ContractHint.pick("한화오션이 공시한 잠수함 공급계약의 계약금액은?", values))
                .contains("doc2");
        assertThat(ContractHint.pick("한화오션이 공시한 FCS 공급계약의 계약금액은?", values))
                .contains("doc1");
    }

    @Test
    void doesNotPickWhenNothingInTheQuestionMatches() {
        Map<String, List<String>> values = candidates(
                "방위사업청", "수상함 1척",
                "방위사업청", "잠수함 1척");

        assertThat(ContractHint.pick("한화오션이 공시한 공급계약의 계약금액은?", values)).isEmpty();
    }

    @Test
    void doesNotPickWhenTheHintFitsSeveralCandidates() {
        // 상대방이 같으면 상대방만으로는 가릴 수 없다.
        Map<String, List<String>> values = candidates(
                "방위사업청", "수상함 1척",
                "방위사업청", "잠수함 1척");

        assertThat(ContractHint.pick("한화오션이 방위사업청과 맺은 공급계약의 계약금액은?", values))
                .isEmpty();
    }

    @Test
    void formWordsDoNotDecideTheWinner() {
        // 계약명에 든 '공급'·'계약'은 질문 자체에 들어 있다. 세면 묻지 않은 계약이 이긴다.
        Map<String, List<String>> values = candidates(
                "㈜삼천리이에스", "연료전지 시스템 공급 계약",
                "㈜울산에너루트2호", "연료전지 시스템 장기유지보수계약(LTSA)");

        assertThat(ContractHint.pick("두산퓨얼셀이 공시한 공급계약의 계약금액은?", values)).isEmpty();
        assertThat(ContractHint.pick("두산퓨얼셀이 울산에너루트2호와 맺은 계약금액은?", values))
                .contains("doc1");
    }

    @Test
    void ignoresCompanyMarksAndSpacing() {
        Map<String, List<String>> values = candidates(
                "㈜삼천리이에스", "연료전지 공급",
                "한국항공우주산업(주)", "유도탄 시제");

        // 질문은 ㈜를 떼고 쓴다.
        assertThat(ContractHint.pick("두산퓨얼셀이 삼천리이에스와 맺은 계약금액은?", values))
                .contains("doc0");
        assertThat(ContractHint.pick("두산퓨얼셀이 한국항공우주산업과 맺은 계약금액은?", values))
                .contains("doc1");
    }

    @Test
    void needsMoreThanOneCandidate() {
        assertThat(ContractHint.pick("무엇이든", candidates("방위사업청", "잠수함 1척"))).isEmpty();
    }
}

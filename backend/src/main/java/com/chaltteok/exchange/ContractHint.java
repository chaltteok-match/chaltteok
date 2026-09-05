package com.chaltteok.exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 같은 날 여러 건일 때 질문이 어느 계약을 가리키는지 고른다.
 *
 * <p>질문에서 상대방·계약명을 뽑아내지 않는다. 어디까지가 이름인지 알 수 없기 때문이다.
 * 대신 <b>후보 문서가 가진 값을 질문에 대어 본다.</b> 값을 낱말로 쪼개 질문에 나오는지 세고,
 * 한 후보가 단독으로 앞서면 그 문서를 고른다.
 *
 * <p>동점이면 고르지 않는다. 두산퓨얼셀은 2025-08-22에 같은 상대(㈜삼천리이에스)와 같은
 * 이름의 계약을 3건 공시했다. 가릴 단서가 원문에 없으므로 답변 불가가 맞다.
 */
final class ContractHint {

    /**
     * 서식·질문 양쪽에 늘 나오는 낱말. 점수로 세면 안 된다.
     *
     * <p>계약명이 {@code 연료전지 시스템 공급 계약}이면 {@code 공급}·{@code 계약}이 낱말로
     * 잡히는데, 이 말은 「공급계약의 계약금액은?」이라는 질문 자체에 들어 있다. 세면 질문이
     * 가리키지 않은 계약이 이긴다.
     */
    private static final Set<String> FRAME_WORDS = Set.of(
            "계약", "공급", "판매", "체결", "해지", "금액", "규모", "공시", "내용",
            "투자", "시설", "신규", "수주", "발주", "기업", "회사", "주식회사",
            "사업", "공사", "제품", "물품", "용역", "매출", "매출액", "비율", "목적");

    /** 낱말 경계. 값에는 괄호·쉼표·가운뎃점이 섞인다. */
    private static final Pattern SEPARATOR = Pattern.compile("[^0-9A-Za-z가-힣]+");

    /** 법인 표기. 질문은 보통 떼고 쓴다. */
    private static final Pattern COMPANY_MARK = Pattern.compile("㈜|\\(주\\)|주식회사");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ContractHint() {
    }

    /**
     * 질문이 가리키는 문서 하나를 고른다. 단독 1위가 없으면 비어 있다.
     *
     * @param candidateValues 문서 id → 그 문서에서 계약을 가릴 수 있는 값들
     */
    static Optional<String> pick(String question, Map<String, List<String>> candidateValues) {
        if (question == null || candidateValues.size() < 2) {
            return Optional.empty();
        }
        // 질문은 공백을 지워 둔다. '삼천리이에스와'처럼 조사가 붙어도 낱말이 그대로 들어 있다.
        String haystack = WHITESPACE.matcher(normalize(question)).replaceAll("");

        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : candidateValues.entrySet()) {
            int score = 0;
            for (String word : distinctiveWords(entry.getValue())) {
                if (haystack.contains(word)) {
                    score++;
                }
            }
            scores.put(entry.getKey(), score);
        }

        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best == 0) {
            return Optional.empty();
        }
        List<String> winners = scores.entrySet().stream()
                .filter(entry -> entry.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        return winners.size() == 1 ? Optional.of(winners.get(0)) : Optional.empty();
    }

    /** 값에서 점수로 셀 낱말만 남긴다. 한 글자와 서식 낱말은 뺀다. */
    private static Set<String> distinctiveWords(List<String> values) {
        Set<String> words = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String token : SEPARATOR.split(normalize(value))) {
                if (token.length() >= 2 && !FRAME_WORDS.contains(token)) {
                    words.add(token);
                }
            }
        }
        return words;
    }

    /** 법인 표기를 떼고 영문 대소문자를 맞춘다. */
    private static String normalize(String text) {
        return COMPANY_MARK.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 서식별로 계약을 가릴 수 있는 항목. */
    static List<String> hintFactKeys(ExchangeTopic topic) {
        List<String> keys = new ArrayList<>();
        switch (topic) {
            case SUPPLY_CONTRACT -> {
                keys.add("contract_counterparty");
                keys.add("contract_name");
            }
            case SUPPLY_TERMINATION -> {
                keys.add("termination_counterparty");
                keys.add("termination_contract_name");
            }
            case FACILITY_INVESTMENT -> {
                keys.add("investment_target");
                keys.add("investment_purpose");
            }
            default -> {
                // 투자판단은 사실이 없어 가릴 항목이 없다.
            }
        }
        return keys;
    }
}

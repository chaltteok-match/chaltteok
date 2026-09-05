package com.chaltteok.exchange;

import com.chaltteok.answer.CorpNameResolver;
import com.chaltteok.answer.QuestionReportDate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문 문장에서 기업·공시일·서식 갈래·항목을 읽어낸다.
 *
 * <p>고급 분류기 이전 단계라 규칙 기반으로 처리하고, 확신할 수 없으면 값을 만들지 않고
 * {@code null}로 둔다. 판단은 Handler가 한다.
 */
final class ExchangeQuestionParser {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");
    /**
     * 최댓값을 묻는 표현. 활용형까지 받는다.
     * '가장 큰'의 '큰'은 어간 '크'와 글자가 달라 어간만 보면 걸리지 않는다.
     */
    /** 이력추적을 묻는 표현. 값 하나가 아니라 바뀐 과정을 답한다. */
    private static final Pattern HISTORY = Pattern.compile(
            "정정|변경|바뀌|바뀐|바뀔|달라진|수정|이력|추이|변화|어떻게\\s*변");

    private static final Pattern LARGEST = Pattern.compile(
            "(?:가장|제일)\\s*(?:크|큰|커|많|많은|높|높은)|최대|최고");

    /**
     * 서식 갈래를 정하는 표현. 긴 표현부터 본다.
     *
     * <p>{@code 계약해지}가 {@code 계약}보다 먼저 걸려야 해지 공시를 체결 공시로 답하지 않는다.
     */
    private static final Map<String, ExchangeTopic> TOPIC_KEYWORDS = new LinkedHashMap<>();

    static {
        TOPIC_KEYWORDS.put("공급계약해지", ExchangeTopic.SUPPLY_TERMINATION);
        TOPIC_KEYWORDS.put("계약해지", ExchangeTopic.SUPPLY_TERMINATION);
        TOPIC_KEYWORDS.put("해지금액", ExchangeTopic.SUPPLY_TERMINATION);
        TOPIC_KEYWORDS.put("신규시설투자", ExchangeTopic.FACILITY_INVESTMENT);
        TOPIC_KEYWORDS.put("시설투자", ExchangeTopic.FACILITY_INVESTMENT);
        TOPIC_KEYWORDS.put("투자금액", ExchangeTopic.FACILITY_INVESTMENT);
        TOPIC_KEYWORDS.put("공급계약", ExchangeTopic.SUPPLY_CONTRACT);
        TOPIC_KEYWORDS.put("판매계약", ExchangeTopic.SUPPLY_CONTRACT);
        TOPIC_KEYWORDS.put("계약금액", ExchangeTopic.SUPPLY_CONTRACT);
        TOPIC_KEYWORDS.put("수주", ExchangeTopic.SUPPLY_CONTRACT);
    }

    /** 이 Handler가 다루는 질문인지 가리는 표현. */
    static final List<String> TOPIC_TERMS = List.copyOf(TOPIC_KEYWORDS.keySet());

    /**
     * 갈래별 항목 표현 → fact_key. 표현이 없으면 그 갈래의 대표 금액을 답한다.
     *
     * <p>{@code 최근매출액}은 계약 상대가 아니라 공시한 기업의 매출액이다. 상대방 매출액은
     * {@code contract_counterparty_recent_revenue}로 따로 있으므로 섞지 않는다.
     */
    private static final Map<ExchangeTopic, Map<String, String>> ITEM_KEYWORDS = Map.of(
            ExchangeTopic.SUPPLY_CONTRACT, new LinkedHashMap<>(Map.of(
                    "매출액대비", "contract_revenue_ratio",
                    "매출액 대비", "contract_revenue_ratio",
                    "최근매출액", "contract_recent_revenue",
                    "최근 매출액", "contract_recent_revenue",
                    "계약상대", "contract_counterparty",
                    "계약금액", "contract_amount")),
            ExchangeTopic.FACILITY_INVESTMENT, new LinkedHashMap<>(Map.of(
                    "자기자본대비", "investment_equity_ratio",
                    "자기자본 대비", "investment_equity_ratio",
                    "자기자본", "investment_equity",
                    "투자목적", "investment_purpose",
                    "투자금액", "investment_amount")),
            ExchangeTopic.SUPPLY_TERMINATION, new LinkedHashMap<>(Map.of(
                    "매출액대비", "termination_revenue_ratio",
                    "해지사유", "termination_reason",
                    "해지 사유", "termination_reason",
                    "해지금액", "termination_amount")));

    /** 항목 표현이 없을 때 답할 대표 금액. */
    private static final Map<ExchangeTopic, String> DEFAULT_FACT_KEYS = Map.of(
            ExchangeTopic.SUPPLY_CONTRACT, "contract_amount",
            ExchangeTopic.FACILITY_INVESTMENT, "investment_amount",
            ExchangeTopic.SUPPLY_TERMINATION, "termination_amount");

    private static final Map<String, String> ITEM_LABELS = Map.ofEntries(
            Map.entry("contract_amount", "계약금액"),
            Map.entry("contract_recent_revenue", "최근매출액"),
            Map.entry("contract_revenue_ratio", "매출액 대비 비율"),
            Map.entry("contract_counterparty", "계약상대"),
            Map.entry("investment_amount", "투자금액"),
            Map.entry("investment_equity", "자기자본"),
            Map.entry("investment_equity_ratio", "자기자본 대비 비율"),
            Map.entry("investment_purpose", "투자목적"),
            Map.entry("termination_amount", "해지금액"),
            Map.entry("termination_revenue_ratio", "매출액 대비 비율"),
            Map.entry("termination_reason", "해지 주요사유"));

    private ExchangeQuestionParser() {
    }

    static ExchangeQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;

        ExchangeTopic topic = resolveTopic(text);
        String factKey = topic == null ? null : resolveFactKey(text, topic);
        LocalDate reportDate = reportDate(text);

        return new ExchangeQuestion(
                corpNames.resolve(text),
                reportDate,
                yearOf(reportDate, text),
                topic,
                factKey,
                factKey == null ? null : ITEM_LABELS.get(factKey),
                LARGEST.matcher(text).find(),
                HISTORY.matcher(text).find());
    }

    /** 등록된 순서대로 본다. 긴 표현을 앞에 둬 '계약해지'가 '계약금액'보다 먼저 걸린다. */
    private static ExchangeTopic resolveTopic(String text) {
        for (Map.Entry<String, ExchangeTopic> entry : TOPIC_KEYWORDS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 항목 표현이 여럿 걸리면 가장 긴 것을 고른다. '최근매출액'이 '매출액대비'에 밀리지 않게 한다. */
    private static String resolveFactKey(String text, ExchangeTopic topic) {
        String best = ITEM_KEYWORDS.get(topic).keySet().stream()
                .filter(text::contains)
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        return best == null ? DEFAULT_FACT_KEYS.get(topic) : ITEM_KEYWORDS.get(topic).get(best);
    }

    /**
     * 공시일. 한글·ISO·점 표기를 같은 날로 읽는다.
     *
     * <p>한글만 읽던 때에는 {@code 2025-07-28}로 물으면 날짜를 못 읽고 그 기업의 다른 공시를
     * 답했다. 답변 불가가 아니라 틀린 금액이 나갔다.
     */
    private static LocalDate reportDate(String text) {
        return QuestionReportDate.parse(text);
    }

    /**
     * 질문이 지정한 연도. 공시일을 읽었으면 그 해로 둔다.
     *
     * <p>{@link #year}는 {@code 년}이 붙은 표기만 센다. ISO·점 표기에서 연도가 비면 한글로
     * 물을 때와 문서를 좁히는 범위가 달라져 같은 공시에 다른 답이 나간다.
     */
    private static Integer yearOf(LocalDate reportDate, String text) {
        // 삼항으로 쓰면 int와 Integer가 섞여 year(text)가 자동 언박싱된다. null이면 터진다.
        if (reportDate != null) {
            return reportDate.getYear();
        }
        return year(text);
    }

    private static Integer year(String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group().replaceAll("[^0-9]", ""));
            if (!years.contains(value)) {
                years.add(value);
            }
        }
        return years.size() == 1 ? years.get(0) : null;
    }
}

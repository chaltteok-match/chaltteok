package com.chaltteok.financial;

import com.chaltteok.answer.CorpNameResolver;

import com.chaltteok.disclosure.model.enums.Scope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 질문 문장에서 기업·연도·항목·연결기준을 읽어낸다.
 *
 * <p>고급 분류기 이전 단계이므로 규칙 기반으로 처리하고, 확신할 수 없으면
 * 값을 만들어 내지 않고 {@code null}로 둔다. 판단은 Handler가 한다.
 */
final class FinancialQuestionParser {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");
    private static final Pattern COMPARISON = Pattern.compile(
            "대비|증감|증가|감소|늘어|늘었|줄어|줄었|비교|성장|얼마나");

    /**
     * 답으로 기업을 요구하는 말과, 비교·최상급 표현.
     *
     * <p>{@code 매출액이 가장 큰 기업은?}·{@code 삼성전자보다 매출액이 큰 기업은?}은 70개사를
     * 다 훑어야 답할 수 있다. 우리가 근거를 댈 수 있는 것은 질문에 이름이 나온 기업뿐이다.
     *
     * <p>{@code A와 B 중 더 큰 곳은?}도 두 표현에 다 걸리지만 기업이 둘이라 비교로 처리한다.
     */
    private static final Pattern ASKS_FOR_A_CORP = Pattern.compile("기업|회사|곳|업체|종목");

    private static final Pattern SUPERLATIVE = Pattern.compile("가장|제일|최대|최소|상위|순위|보다");

    /**
     * {@code A와 B}처럼 둘을 잇는 말. 기업을 하나밖에 못 풀었는데 이 꼴이면 상대 기업을
     * 못 알아본 것이다. 한 기업 수치로 비교 답을 만들지 않도록 답변 불가로 둔다.
     *
     * <p>{@code 전년과 비교}처럼 기간을 잇는 말은 뺀다. 연도 간 비교는 기존 경로가 처리한다.
     */
    /** 기간을 잇는 말. {@code 전년과 비교}·{@code 2023년과 비교}는 연도 간 비교다. */
    private static final String NOT_A_PERIOD =
            "(?<!전년)(?<!작년)(?<!전기)(?<!당기)(?<!직전)(?<!분기)(?<!\\d년)";

    private static final Pattern JOINED_PAIR = Pattern.compile(
            "[가-힣A-Za-z0-9]{2,}" + NOT_A_PERIOD + "\\s*(와|과)\\s+[가-힣A-Za-z0-9]{2,}");

    /**
     * 질문에서 인정하는 항목 표현. 문서 파서의 인정 라벨과 같은 범위로 좁혀 둔다.
     * {@code 매출}·{@code 순이익} 같은 조각은 넣지 않는다.
     * {@code 주당순이익}이 {@code 순이익}으로, {@code 매출총이익}이 {@code 매출}로 잡히면
     * 문서 쪽에서 막은 오탐이 질문 쪽에서 다시 열린다.
     */
    private static final Map<String, String> ITEM_KEYWORDS = Map.ofEntries(
            Map.entry("매출액", "revenue"),
            Map.entry("영업수익", "revenue"),
            Map.entry("영업이익", "operating_profit"),
            Map.entry("당기순이익", "net_income"),
            Map.entry("자산총계", "total_assets"),
            Map.entry("총자산", "total_assets"),
            Map.entry("부채총계", "total_liabilities"),
            Map.entry("총부채", "total_liabilities"),
            Map.entry("자본총계", "total_equity"),
            Map.entry("총자본", "total_equity"));

    /**
     * 인정 표현을 포함하지만 뜻이 다른 표현. 이 중 하나가 더 길게 걸리면 항목을 만들지 않는다.
     * 예: {@code 총자산회전율}은 {@code 총자산}을 품고 있지만 금액이 아니라 비율이다.
     */
    private static final Set<String> CONFUSABLE_TERMS = Set.of(
            "매출총이익", "매출원가", "매출채권", "매출액영업이익률", "매출액순이익률",
            "영업이익률", "영업이익증가율",
            "주당순이익", "기본주당순이익", "희석주당순이익",
            "총자산회전율", "총자산이익률", "총자산순이익률", "총자산증가율",
            "자산총계증가율", "부채총계증가율", "자본총계증가율");

    /** 인정 표현과 혼동 표현을 함께 두고 가장 긴 것을 고른다. */
    private static final List<String> ALL_TERMS = Stream
            .concat(ITEM_KEYWORDS.keySet().stream(), CONFUSABLE_TERMS.stream())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private FinancialQuestionParser() {
    }

    static FinancialQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;

        List<String> mentioned = corpNames.resolveAll(text);
        // 가장 긴 표현이 혼동 표현이면 항목을 만들지 않는다.
        // '주당순이익'에 당기순이익을, '총자산회전율'에 자산총계를 답하지 않기 위해서다.
        String matched = ALL_TERMS.stream().filter(text::contains).findFirst().orElse(null);
        String itemLabel = matched != null && ITEM_KEYWORDS.containsKey(matched) ? matched : null;
        String factKey = itemLabel == null ? null : ITEM_KEYWORDS.get(itemLabel);

        List<Integer> years = years(text);
        Integer targetYear = years.isEmpty() ? null : years.get(years.size() - 1);
        Integer baseYear = null;
        if (targetYear != null && COMPARISON.matcher(text).find()) {
            // 연도를 두 개 쓴 질문이면 앞의 연도를 비교 기준으로 본다.
            baseYear = years.size() >= 2 ? years.get(0) : targetYear - 1;
        }

        boolean separate = text.contains("별도") || text.contains("개별");
        boolean consolidated = text.contains("연결");
        Scope scope = separate ? Scope.SEPARATE : Scope.CONSOLIDATED;

        // 기업이 둘 이상이면 질문에 나온 기업끼리 비교하면 된다. 하나 이하일 때만 전체 순위다.
        // 기업이 둘 이상이면 질문에 나온 기업끼리 비교하면 된다. 하나 이하일 때만 전체 순위다.
        boolean wantsCorpRanking = mentioned.size() < 2
                && ASKS_FOR_A_CORP.matcher(text).find()
                && SUPERLATIVE.matcher(text).find();

        // 상대 기업을 못 알아본 채 한 기업 수치로 비교 답을 만들지 않는다.
        boolean lostCounterpart = mentioned.size() == 1
                && years.size() < 2
                && JOINED_PAIR.matcher(text).find()
                && COMPARISON.matcher(text).find();

        return new FinancialQuestion(mentioned, targetYear, baseYear, factKey, itemLabel,
                scope, separate || consolidated, wantsCorpRanking, lostCounterpart);
    }


    private static List<Integer> years(String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(text);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group().replaceAll("[^0-9]", ""));
            if (!years.contains(year)) {
                years.add(year);
            }
        }
        years.sort(Comparator.naturalOrder());
        return years;
    }
}

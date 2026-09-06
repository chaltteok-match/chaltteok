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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 질문 문장에서 기업·연도·항목·연결기준을 읽어낸다.
 *
 * <p>고급 분류기 이전 단계이므로 규칙 기반으로 처리하고, 확신할 수 없으면
 * 값을 만들어 내지 않고 {@code null}로 둔다. 판단은 Handler가 한다.
 */
final class FinancialQuestionParser {

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");

    /**
     * {@code 년}이 붙지 않은 네 자리 연도. {@code 삼성전자 매출액 2024}처럼 쓴다.
     *
     * <p>{@link #YEAR}로 하나도 못 찾았을 때만 본다. 앞뒤에 숫자·쉼표·소수점·퍼센트가 붙으면
     * 연도가 아니라 금액이나 종목코드의 일부다.
     */
    private static final Pattern BARE_YEAR = Pattern.compile(
            "(?<![0-9,.])(19|20)\\d{2}(?![0-9,.%])");
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
    /**
     * {@code 와}·{@code 과} 양옆에 오는 말 중 기업일 수 없는 것.
     *
     * <p>{@code 연결과 별도}·{@code 매출액과 영업이익}·{@code 전년과 비교}는 기업을 잇는 말이
     * 아니다. 한쪽이라도 이 목록에 있으면 상대 기업을 잃은 것으로 보지 않는다.
     */
    private static final List<String> NOT_A_COMPANY = List.of(
            "연결기준", "별도기준", "개별기준", "연결", "별도", "개별", "기준",
            "전년", "작년", "전기", "당기", "전분기", "직전", "올해", "금년",
            "매출액", "영업수익", "영업이익", "당기순이익", "순이익",
            "자산총계", "총자산", "부채총계", "총부채", "자본총계", "총자본",
            "수치", "실적", "금액", "비율", "증감");

    private static final Pattern JOINED_PAIR = Pattern.compile("(와|과)\\s+");

    /**
     * 질문에서 인정하는 항목 표현.
     *
     * <p>{@code 매출}·{@code 순이익}처럼 짧은 조각도 받는다. 실무에서 더 흔히 쓰는 말이라
     * 빼 두면 같은 것을 묻는 질문의 절반이 Handler에 닿지 못한다. 대신 이 조각을 품은
     * 다른 뜻의 말은 {@link #CONFUSABLE_TERMS}에 모두 적어 둔다. 가장 긴 표현이 이기므로
     * {@code 주당순이익}은 {@code 순이익}으로, {@code 매출총이익}은 {@code 매출}로 잡히지 않는다.
     */
    private static final Map<String, String> ITEM_KEYWORDS = Map.ofEntries(
            Map.entry("매출액", "revenue"),
            Map.entry("매출", "revenue"),
            Map.entry("영업수익", "revenue"),
            Map.entry("영업이익", "operating_profit"),
            Map.entry("영업손익", "operating_profit"),
            Map.entry("당기순이익", "net_income"),
            Map.entry("순이익", "net_income"),
            Map.entry("자산총계", "total_assets"),
            Map.entry("총자산", "total_assets"),
            Map.entry("자산 규모", "total_assets"),
            Map.entry("자산규모", "total_assets"),
            Map.entry("부채총계", "total_liabilities"),
            Map.entry("총부채", "total_liabilities"),
            Map.entry("부채 규모", "total_liabilities"),
            Map.entry("부채규모", "total_liabilities"),
            Map.entry("자본총계", "total_equity"),
            Map.entry("총자본", "total_equity"),
            Map.entry("자기자본", "total_equity"),
            Map.entry("자본 규모", "total_equity"),
            Map.entry("자본규모", "total_equity"));

    /**
     * 인정 표현을 포함하지만 뜻이 다른 표현. 이 중 하나가 더 길게 걸리면 항목을 만들지 않는다.
     * 예: {@code 총자산회전율}은 {@code 총자산}을 품고 있지만 금액이 아니라 비율이다.
     *
     * <p>비율·증가율은 요약재무정보에 없는 값이다. 항목으로 잡으면 분자에 해당하는 금액을
     * 비율인 것처럼 답하게 된다.
     */
    private static final Set<String> CONFUSABLE_TERMS = Set.of(
            "매출총이익", "매출총이익률", "매출원가", "매출원가율", "매출채권", "매출채권회전율",
            "매출액증가율", "매출증가율", "매출비중", "매출구성",
            "영업이익증가율", "영업수익증가율",
            "주당순이익", "기본주당순이익", "희석주당순이익",
            "순이익증가율", "당기순이익증가율",
            "자기자본증가율", "자기자본회전율",
            "총자산회전율", "총자산증가율",
            "부채총계증가율", "자산총계증가율", "자본총계증가율");

    /**
     * 항목 뒤에 붙어 비율을 만드는 꼬리. 이 꼬리가 붙으면 금액이 아니다.
     *
     * <p>{@link #CONFUSABLE_TERMS}에 다 적을 수 없다. 항목마다 만들어지기 때문이다.
     * 목록에 없는 표기가 새로 나와도 <b>항목 바로 뒤 세 글자 안에 {@code 률}·{@code 율}이
     * 있으면</b> 항목으로 보지 않는다. 세 글자면 {@code 률}·{@code 비율}·{@code 증가율}·
     * {@code 회전율}이 모두 들어온다. 목록은 이 검사로 못 잡는 표기를 위해 남긴다
     * ({@code 총자산순이익률}처럼 꼬리가 세 글자를 넘는 것, {@code 매출채권}처럼 율이 없는 것).
     */
    private static final int RATIO_TAIL_WINDOW = 3;

    /** 인정 표현과 혼동 표현을 함께 두고 가장 긴 것을 고른다. 공백은 지우고 견준다. */
    private static final List<String> ALL_TERMS = Stream
            .of(ITEM_KEYWORDS.keySet().stream(),
                    CONFUSABLE_TERMS.stream(),
                    FinancialRatio.keywordMap().keySet().stream())
            .flatMap(terms -> terms)
            .map(FinancialQuestionParser::compact)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    /** 공백을 지운 비율 표. 항목 표와 같은 자리에서 견준다. */
    private static final Map<String, FinancialRatio> COMPACT_RATIOS =
            FinancialRatio.keywordMap().entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> compact(entry.getKey()),
                            Map.Entry::getValue,
                            (first, second) -> first));

    /** 공백을 지운 표기로 찾기 위한 표. {@code 자산 규모}와 {@code 자산규모}를 같게 본다. */
    private static final Map<String, String> COMPACT_ITEMS = ITEM_KEYWORDS.entrySet().stream()
            .collect(Collectors.toMap(
                    entry -> compact(entry.getKey()),
                    Map.Entry::getValue,
                    (first, second) -> first));

    private FinancialQuestionParser() {
    }

    static FinancialQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;

        List<String> mentioned = corpNames.resolveAll(text);
        // 가장 긴 표현이 혼동 표현이면 항목을 만들지 않는다.
        // '주당순이익'에 당기순이익을, '총자산회전율'에 자산총계를 답하지 않기 위해서다.
        // 표현을 찾을 때만 공백을 지운다. `매출 증가율`과 `매출증가율`을 같게 보기 위해서다.
        String compacted = compact(text);
        String matched = ALL_TERMS.stream().filter(compacted::contains).findFirst().orElse(null);
        FinancialRatio ratio = matched == null ? null : COMPACT_RATIOS.get(matched);
        String itemLabel = matched != null && COMPACT_ITEMS.containsKey(matched) ? matched : null;
        // 계산할 수 있는 비율이면 꼬리 검사를 하지 않는다. 그 꼬리가 곧 비율의 이름이다.
        if (ratio == null && itemLabel != null && hasRatioTail(compacted, itemLabel)) {
            itemLabel = null;
        }
        String factKey = itemLabel == null ? null : COMPACT_ITEMS.get(itemLabel);
        if (ratio != null) {
            // 비율도 정기공시 항목을 묻는 질문이다. 분자를 대표 항목으로 두어 Handler가 나선다.
            itemLabel = ratio.label();
            factKey = ratio.numeratorKey();
        }

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
                && COMPARISON.matcher(text).find()
                && joinsTwoUnknownNames(text);

        return new FinancialQuestion(mentioned, targetYear, baseYear, factKey, itemLabel,
                scope, separate || consolidated, wantsCorpRanking, lostCounterpart, ratio);
    }


    /**
     * {@code A와 B} 꼴에서 양옆이 둘 다 우리가 아는 낱말이 아닌지 본다.
     *
     * <p>기업을 하나밖에 못 풀었는데 이 꼴이면 상대 기업을 못 알아본 것이다.
     * {@code 연결과 별도}처럼 한쪽이 아는 낱말이면 기업을 잇는 말이 아니다.
     */
    private static boolean joinsTwoUnknownNames(String text) {
        Matcher matcher = JOINED_PAIR.matcher(text);
        while (matcher.find()) {
            String before = text.substring(0, matcher.start());
            String after = text.substring(matcher.end());
            if (nameLikeLength(before, true) >= 2 && nameLikeLength(after, false) >= 2
                    && !endsWithKnownWord(before) && !startsWithKnownWord(after)) {
                return true;
            }
        }
        return false;
    }

    /** 조사 바로 앞뒤에 붙은 글자 수. 이름이라면 두 글자는 된다. */
    private static int nameLikeLength(String text, boolean fromEnd) {
        int count = 0;
        for (int step = 0; step < text.length(); step++) {
            char letter = fromEnd ? text.charAt(text.length() - 1 - step) : text.charAt(step);
            if (!Character.isLetterOrDigit(letter)) {
                break;
            }
            count++;
        }
        return count;
    }

    private static boolean endsWithKnownWord(String before) {
        return NOT_A_COMPANY.stream().anyMatch(before::endsWith)
                || before.matches(".*\\d년\\s*");
    }

    private static boolean startsWithKnownWord(String after) {
        String trimmed = after.stripLeading();
        return NOT_A_COMPANY.stream().anyMatch(trimmed::startsWith);
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    /** 항목 바로 뒤 세 글자 안에 {@code 률}·{@code 율}이 있으면 비율이다. */
    private static boolean hasRatioTail(String compacted, String item) {
        int at = compacted.indexOf(item);
        while (at >= 0) {
            int from = at + item.length();
            String tail = compacted.substring(
                    from, Math.min(compacted.length(), from + RATIO_TAIL_WINDOW));
            if (tail.indexOf('률') >= 0 || tail.indexOf('율') >= 0) {
                return true;
            }
            at = compacted.indexOf(item, at + 1);
        }
        return false;
    }

    private static List<Integer> years(String text) {
        List<Integer> years = collect(YEAR, text);
        return years.isEmpty() ? collect(BARE_YEAR, text) : years;
    }

    private static List<Integer> collect(Pattern pattern, String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
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

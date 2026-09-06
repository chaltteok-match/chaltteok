package com.chaltteok.answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문에 쓰인 기업 표기를 적재된 공식 사명({@code corp_name})으로 되돌린다.
 *
 * <p>평가 질의가 어떤 표기를 쓸지 정해져 있지 않다. 공식 사명뿐 아니라 상장 표기
 * ({@code 현대차}), 한글 음차({@code 에스케이하이닉스}), 종목코드({@code 005930})로 물어도
 * 같은 기업을 찾아야 한다. 표기 하나가 어긋나면 그 질문은 통째로 답변 불가가 된다.
 *
 * <p>표기를 하나의 표준형으로 접어서 비교한다. 한글 음차를 영문 약자 쪽으로 모으고
 * (`에스케이` → `SK`), 공백·점·붙임표를 지우고, 영문을 대문자로 올린다. 질문과 사명에
 * 같은 변환을 걸면 `에스케이하이닉스`와 `SK하이닉스`가 같은 문자열이 된다.
 *
 * <p>여러 기업이 같은 표기로 걸리면 고르지 않는다. `현대`는 9개사에 걸리므로
 * 답을 찍는 대신 기업을 특정하지 못한 것으로 둔다.
 */
public final class CorpNameResolver {

    /**
     * 한글 음차 → 영문 약자. 표준형은 영문 쪽이다.
     *
     * <p>긴 표기를 먼저 바꾼다. `에스케이`를 `에스`보다 뒤에 두면 `SK`가 만들어지지 않는다.
     */
    private static final Map<String, String> TRANSLITERATIONS = new java.util.LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("에스케이", "SK"), Map.entry("엘지", "LG"), Map.entry("케이티", "KT"),
                Map.entry("케이비", "KB"), Map.entry("씨제이", "CJ"), Map.entry("엘에스", "LS"),
                Map.entry("에이치디", "HD"), Map.entry("지에스", "GS"), Map.entry("디비", "DB"),
                Map.entry("오씨아이", "OCI"), Map.entry("엘아이지", "LIG"), Map.entry("에이치엠엠", "HMM"),
                Map.entry("엔씨", "NC"), Map.entry("포스코", "POSCO"), Map.entry("네이버", "NAVER"),
                Map.entry("에스디아이", "SDI"), Map.entry("씨엔에스", "CNS"), Map.entry("이앤에이", "E&A"),
                Map.entry("제이와이피", "JYP"), Map.entry("와이지", "YG"), Map.entry("에스엠", "SM"),
                Map.entry("케이씨씨", "KCC"), Map.entry("엔에이치", "NH"), Map.entry("아이비케이", "IBK"),
                Map.entry("에이치엘", "HL"), Map.entry("비지에프", "BGF"), Map.entry("에스피씨", "SPC"));
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length())
                        .reversed())
                .forEach(e -> TRANSLITERATIONS.put(e.getKey(), e.getValue()));
    }

    /** 지주회사 접미어. `포스코`로 물어도 `POSCO홀딩스`를 찾게 한다. */
    private static final List<String> GROUP_SUFFIXES = List.of("홀딩스", "지주", "그룹");

    /** 사명·상장 표기·코드는 그대로 쓴다. `NC`·`KT`·`SM`처럼 두 글자인 사명이 있다. */
    private static final int MIN_ALIAS_CHARS = 2;
    /**
     * 잘라 만든 표기는 세 글자부터 쓴다.
     *
     * <p>`신한지주`에서 접미어를 떼면 `신한`이 되는데, 이러면 `신한카드`·`신한은행` 질문이
     * 신한지주로 잡힌다. 우리가 답할 수 없는 회사에 엉뚱한 답을 내는 쪽이 못 찾는 쪽보다 나쁘다.
     */
    private static final int MIN_DERIVED_CHARS = 3;

    /**
     * 뒤에 붙으면 다른 법인이 되는 표기.
     *
     * <p>표기가 부분 문자열로 걸려 <b>다른 회사의 수치를 답하고 있었다.</b>
     * {@code 카카오뱅크}가 {@code 카카오}로, {@code 네이버파이낸셜}이 {@code NAVER}로 잡혔다.
     * 못 찾는 쪽이 틀린 답을 자신 있게 내는 쪽보다 낫다({@link #MIN_DERIVED_CHARS}와 같은 판단이다).
     *
     * <p>{@code 기아자동차}처럼 같은 회사의 옛 이름은 막지 않는다. 금융·계열 자회사를 만드는
     * 표기만 담는다.
     */
    private static final List<String> AFFILIATE_SUFFIXES = List.of(
            "뱅크", "페이", "파이낸셜", "증권", "카드", "캐피탈", "저축은행", "은행",
            "생명", "화재", "손해보험", "손보", "해상", "자산운용", "인베스트먼트",
            "벤처스", "모빌리티", "엔터테인먼트", "라이프");

    private static final Pattern NOISE = Pattern.compile("[\\s.\\-·_'\"()]+");
    /** 영문·숫자 앞머리. `SK하이닉스`에서 `하이닉스`를 뽑는다. */
    private static final Pattern LATIN_PREFIX = Pattern.compile("^[A-Z0-9&]{2,6}(?=[가-힣])");

    private final Map<String, Set<String>> aliasToCorpNames;

    private CorpNameResolver(Map<String, Set<String>> aliasToCorpNames) {
        this.aliasToCorpNames = aliasToCorpNames;
    }

    /** 적재된 법인 식별자로 만든다. */
    public static CorpNameResolver of(List<CorpIdentity> identities) {
        Map<String, Set<String>> index = new HashMap<>();
        for (CorpIdentity identity : identities) {
            if (identity == null || isBlank(identity.corpName())) {
                continue;
            }
            for (String alias : aliasesOf(identity)) {
                index.computeIfAbsent(alias, key -> new LinkedHashSet<>()).add(identity.corpName());
            }
        }
        return new CorpNameResolver(index);
    }

    /** 사명만 있을 때. 별칭 없이 사명 표기만 푼다. */
    public static CorpNameResolver ofNames(List<String> corpNames) {
        return of(corpNames.stream().map(name -> new CorpIdentity(name, name, null, null)).toList());
    }

    /**
     * 질문이 가리키는 공식 사명. 찾지 못하거나 여러 기업에 걸리면 {@code null}.
     *
     * <p>가장 긴 표기를 고른다. `삼성전자`와 `삼성전기`가 함께 걸릴 일은 없지만,
     * `LG`와 `LG이노텍`처럼 한쪽이 다른 쪽에 포함되는 표기는 긴 쪽이 옳다.
     */
    public String resolve(String question) {
        if (isBlank(question)) {
            return null;
        }
        String folded = fold(question);
        String bestAlias = null;
        for (String alias : aliasToCorpNames.keySet()) {
            if (!contains(folded, alias)) {
                continue;
            }
            if (bestAlias == null || alias.length() > bestAlias.length()) {
                bestAlias = alias;
            }
        }
        if (bestAlias == null) {
            return null;
        }
        Set<String> matched = aliasToCorpNames.get(bestAlias);
        // 같은 표기에 여러 기업이 걸리면 찍지 않는다.
        if (matched.size() != 1) {
            return null;
        }
        String corpName = matched.iterator().next();
        return asksAboutAffiliate(folded, corpName) ? null : corpName;
    }

    /**
     * 질문에 나온 기업을 모두 푼다. 나온 순서대로, 중복 없이 돌려준다.
     *
     * <p>{@link #resolve}는 가장 긴 표기 하나만 고른다. {@code A와 B를 비교하면}처럼 기업이
     * 둘인 질문에서 한쪽만 잡으면 한 기업 수치만 근거로 들고 비교 답을 만들게 된다.
     *
     * <p>표기가 겹치면 긴 쪽을 쓴다. {@code LG이노텍}을 잡은 자리에서 {@code LG}를 다시 잡지
     * 않는다. 여러 기업에 걸리는 표기는 {@link #resolve}와 같이 고르지 않는다.
     */
    public List<String> resolveAll(String question) {
        if (isBlank(question)) {
            return List.of();
        }
        String folded = fold(question);
        boolean[] covered = new boolean[folded.length()];
        Map<Integer, String> byPosition = new TreeMap<>();
        List<String> longestFirst = aliasToCorpNames.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String alias : longestFirst) {
            Set<String> matched = aliasToCorpNames.get(alias);
            if (matched.size() != 1) {
                continue;
            }
            for (int at : occurrences(folded, alias)) {
                int end = at + alias.length();
                if (anyCovered(covered, at, end)) {
                    continue;
                }
                String corpName = matched.iterator().next();
                if (asksAboutAffiliate(folded, corpName)) {
                    continue;
                }
                Arrays.fill(covered, at, end, true);
                byPosition.put(at, corpName);
            }
        }
        return byPosition.values().stream().distinct().toList();
    }

    /**
     * 질문에 기업이 둘 이상일 때, <b>그 공시를 낸 쪽</b>을 고른다.
     *
     * <p>{@link #resolve}는 표기가 긴 쪽을 고른다. 상대 기업을 함께 부르는 질문에서는
     * 그쪽이 이긴다. 하이브 지분을 미래에셋증권이 보유한 공시를 물으면 미래에셋증권을
     * 잡고, 그 회사의 다른 공시를 뒤진다.
     *
     * <p>후보 중 조건에 맞는 문서를 가진 쪽이 <b>하나뿐일 때만</b> 고른다. 둘 다 갖고 있으면
     * 어느 쪽을 묻는지 알 수 없으므로 기존 규칙으로 돌아간다.
     *
     * @param hasMatchingDocument 그 기업이 질문의 조건에 맞는 문서를 가졌는지
     */
    public String resolve(String question, java.util.function.Predicate<String> hasMatchingDocument) {
        List<String> candidates = resolveAll(question);
        if (candidates.size() < 2) {
            return resolve(question);
        }
        List<String> filed = candidates.stream().filter(hasMatchingDocument).toList();
        return filed.size() == 1 ? filed.get(0) : resolve(question);
    }

    private static List<Integer> occurrences(String text, String alias) {
        List<Integer> found = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = text.indexOf(alias, from);
            if (at < 0) {
                return found;
            }
            if (!digitNeighbour(text, at, alias)) {
                found.add(at);
            }
            from = at + 1;
        }
    }

    /** 코드 표기는 앞뒤에 숫자가 붙으면 다른 숫자의 일부다. {@link #contains}와 같은 규칙이다. */
    private static boolean digitNeighbour(String text, int at, String alias) {
        if (!alias.chars().allMatch(Character::isDigit)) {
            return false;
        }
        boolean digitBefore = at > 0 && Character.isDigit(text.charAt(at - 1));
        int after = at + alias.length();
        return digitBefore || (after < text.length() && Character.isDigit(text.charAt(after)));
    }

    private static boolean anyCovered(boolean[] covered, int start, int end) {
        for (int index = start; index < end; index++) {
            if (covered[index]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 코드는 앞뒤에 숫자가 붙지 않을 때만 인정한다. 접수번호처럼 긴 숫자 안에
     * 우연히 들어 있는 6자리를 종목코드로 읽으면 엉뚱한 기업이 나온다.
     */
    private static boolean contains(String text, String alias) {
        int from = 0;
        while (true) {
            int at = text.indexOf(alias, from);
            if (at < 0) {
                return false;
            }
            if (!digitNeighbour(text, at, alias)) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * 질문이 이 회사가 아니라 그 이름을 앞에 단 다른 법인을 묻는지.
     *
     * <p>이 회사의 표기 뒤에 자회사를 만드는 말이 붙었는지 본다. 붙여 만든 이름이 <b>이 회사의
     * 다른 표기이면</b> 그 회사가 맞다. {@code 미래에셋}+{@code 증권}은 {@code 미래에셋증권}이라
     * 통과하고, {@code 미래에셋}+{@code 생명}은 색인에 없어 막힌다.
     *
     * <p>정식 사명만 보면 모자란다. 표기에는 앞을 자른 조각({@code NAV})이 섞여 있어
     * 그쪽이 먼저 걸리기도 한다.
     */
    private boolean asksAboutAffiliate(String folded, String corpName) {
        for (Map.Entry<String, Set<String>> entry : aliasToCorpNames.entrySet()) {
            if (!entry.getValue().contains(corpName)) {
                continue;
            }
            for (String suffix : AFFILIATE_SUFFIXES) {
                String extended = entry.getKey() + suffix;
                if (folded.contains(extended) && !aliasToCorpNames.containsKey(extended)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> aliasesOf(CorpIdentity identity) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String name : List.of(identity.corpName(), nullToEmpty(identity.listedName()))) {
            if (isBlank(name)) {
                continue;
            }
            String folded = fold(name);
            addAlias(aliases, folded, MIN_ALIAS_CHARS);
            addPrefixes(aliases, folded);
            for (String suffix : GROUP_SUFFIXES) {
                if (folded.endsWith(suffix)) {
                    addAlias(aliases,
                            folded.substring(0, folded.length() - suffix.length()),
                            MIN_DERIVED_CHARS);
                }
            }
            Matcher prefix = LATIN_PREFIX.matcher(folded);
            if (prefix.find()) {
                addAlias(aliases, folded.substring(prefix.end()), MIN_DERIVED_CHARS);
            }
        }
        addAlias(aliases, nullToEmpty(identity.stockCode()), MIN_ALIAS_CHARS);
        addAlias(aliases, nullToEmpty(identity.corpCode()), MIN_ALIAS_CHARS);
        return aliases;
    }

    /**
     * 앞에서 자른 줄임말. `한화에어로`·`삼성바이오`처럼 실제로 쓰는 표기다.
     *
     * <p>여러 기업에 걸리는 조각은 색인에서 자동으로 거부된다. `삼성전`은 삼성전자와
     * 삼성전기에 함께 걸리므로 고르지 않는다.
     */
    private static void addPrefixes(Set<String> aliases, String alias) {
        if (alias.chars().allMatch(Character::isDigit)) {
            return;
        }
        for (int length = MIN_DERIVED_CHARS; length < alias.length(); length++) {
            aliases.add(alias.substring(0, length));
        }
    }

    private static void addAlias(Set<String> aliases, String alias, int minChars) {
        if (alias != null && alias.length() >= minChars) {
            aliases.add(alias);
        }
    }

    /** 표기를 표준형으로 접는다. 질문과 사명에 같은 변환을 건다. */
    static String fold(String text) {
        String folded = NOISE.matcher(text).replaceAll("");
        for (Map.Entry<String, String> entry : TRANSLITERATIONS.entrySet()) {
            folded = folded.replace(entry.getKey(), entry.getValue());
        }
        return folded.toUpperCase(java.util.Locale.ROOT);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 색인에 담긴 표기. 진단과 테스트용이다. */
    List<String> aliases() {
        return new ArrayList<>(aliasToCorpNames.keySet());
    }
}

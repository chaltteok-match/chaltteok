package com.chaltteok.answer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return matched.size() == 1 ? matched.iterator().next() : null;
    }

    /**
     * 코드는 앞뒤에 숫자가 붙지 않을 때만 인정한다. 접수번호처럼 긴 숫자 안에
     * 우연히 들어 있는 6자리를 종목코드로 읽으면 엉뚱한 기업이 나온다.
     */
    private static boolean contains(String text, String alias) {
        if (!alias.chars().allMatch(Character::isDigit)) {
            return text.contains(alias);
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(alias, from);
            if (at < 0) {
                return false;
            }
            boolean digitBefore = at > 0 && Character.isDigit(text.charAt(at - 1));
            int after = at + alias.length();
            boolean digitAfter = after < text.length() && Character.isDigit(text.charAt(after));
            if (!digitBefore && !digitAfter) {
                return true;
            }
            from = at + 1;
        }
    }

    private static Set<String> aliasesOf(CorpIdentity identity) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String name : List.of(identity.corpName(), nullToEmpty(identity.listedName()))) {
            if (isBlank(name)) {
                continue;
            }
            String folded = fold(name);
            addAlias(aliases, folded);
            for (String suffix : GROUP_SUFFIXES) {
                if (folded.endsWith(suffix)) {
                    addAlias(aliases, folded.substring(0, folded.length() - suffix.length()));
                }
            }
            Matcher prefix = LATIN_PREFIX.matcher(folded);
            if (prefix.find()) {
                addAlias(aliases, folded.substring(prefix.end()));
            }
        }
        addAlias(aliases, nullToEmpty(identity.stockCode()));
        addAlias(aliases, nullToEmpty(identity.corpCode()));
        return aliases;
    }

    private static void addAlias(Set<String> aliases, String alias) {
        // 한두 글자는 다른 기업 이름 안에 너무 쉽게 들어간다.
        if (alias != null && alias.length() >= 2) {
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

package com.chaltteok.answer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 질문에서 공시일(연·월·일)을 읽는다.
 *
 * <p>한글({@code yyyy년 M월 d일}), ISO({@code yyyy-MM-dd}), 점({@code yyyy.MM.dd})을
 * 같은 {@link LocalDate}로 본다. 일이 없으면 날짜로 두지 않는다.
 * 달력에 없는 날({@code 2월 30일})도 읽지 않는다.
 */
public final class QuestionReportDate {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일"),
            Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})"),
            Pattern.compile("(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})")
    );

    private static final Pattern DATE_HINT = Pattern.compile(
            "\\d{4}년\\s*\\d{1,2}월|\\d{4}[-.]\\d{1,2}"
    );
    /** 거래소공시와 같이 {@code 년}이 붙은 네 자리만 센다. 종목코드·금액과 구분한다. */
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");

    private QuestionReportDate() {
    }

    public static LocalDate parse(String question) {
        if (question == null) {
            return null;
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(question);
            if (!matcher.find()) {
                continue;
            }
            try {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                );
            } catch (DateTimeException | NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 연월을 적었는데 일로 읽히지 않는 경우. 날짜를 생략한 단건 조회로 넘기지 않는다.
     */
    public static boolean mentionsDate(String question) {
        return question != null && DATE_HINT.matcher(question).find();
    }

    /**
     * 질문이 가리키는 연도. {@code 2024년}처럼 연만 있을 때 그 해 공시로 좁힌다.
     *
     * <p>연이 없거나 서로 다른 연이 둘 이상이면 {@code null}이다. 공시일(연·월·일)은
     * {@link #parse}가 먼저 읽는다.
     */
    public static Integer parseYear(String question) {
        if (question == null) {
            return null;
        }
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(question);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group().replaceAll("[^0-9]", ""));
            if (!years.contains(value)) {
                years.add(value);
            }
        }
        return years.size() == 1 ? years.get(0) : null;
    }
}

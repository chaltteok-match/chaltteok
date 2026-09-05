package com.chaltteok.answer;

import java.time.DateTimeException;
import java.time.LocalDate;
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
}

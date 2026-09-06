package com.chaltteok.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공시로 답할 수 없는 질문을 Handler에 넘기기 전에 가려낸다.
 *
 * <p>「정보한계대응」이 평가 항목이고, 코퍼스로 답할 수 없는 질의가 섞여 들어온다고
 * 주최측이 밝혔다. 답할 수 없다고만 하지 않고 <b>무엇이 없어서인지</b> 밝힌다.
 *
 * <p>기업 범위는 여기서 보지 않는다. {@link CorpNameResolver}가 코퍼스 기업을 찾지 못하면
 * 어느 Handler도 나서지 않아 이미 답변 불가로 간다.
 */
public final class QuestionScope {

    /** 답변 불가 사유. */
    public enum Kind {
        /** 공시에 없는 미래 예측·투자 의견 */
        FORECAST,
        /** 코퍼스가 담지 못하는 기간 */
        PERIOD
    }

    /**
     * 코퍼스가 담은 값의 연도 범위.
     *
     * <p>접수는 2023-01-01부터지만, 정기공시 요약재무정보 표가 한 번에 세 기간을 담아
     * 2020년치 값까지 들어 있다. 접수 연도로 자르면 답할 수 있는 질문을 막는다.
     */
    static final int EARLIEST_YEAR = 2020;
    static final int LATEST_YEAR = 2026;

    /**
     * 앞날을 묻거나 판단을 요구하는 표현.
     *
     * <p>공시 용어와 겹치지 않는 말만 담는다. {@code 예정}은 자기주식 취득예정금액처럼
     * 공시에 실제로 있는 값이라 넣지 않는다.
     */
    private static final Pattern FORECAST = Pattern.compile(
            "전망|예측|예상|추정치|추정해|목표주가|적정주가|사야|팔아야|매수 ?의견|매도 ?의견"
                    + "|투자 ?의견|투자 ?판단해|추천|유망|오를까|내릴까|어떻게 ?될까|괜찮을까");

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}\\s*년");

    private QuestionScope() {
    }

    /**
     * 답할 수 없는 질문이면 사유를 돌려준다.
     *
     * @param question 원 질문
     */
    public static Optional<OutOfScope> check(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        if (FORECAST.matcher(question).find()) {
            return Optional.of(new OutOfScope(
                    Kind.FORECAST,
                    "공시에 없는 미래 예측·투자 의견을 묻는 질문으로 판단",
                    "공시에 근거하지 않은 미래 예측과 투자 의견은 제공하지 않습니다."));
        }

        List<Integer> years = yearsIn(question);
        if (!years.isEmpty() && years.stream().noneMatch(QuestionScope::inRange)) {
            String asked = years.stream().map(String::valueOf).reduce((a, b) -> a + "·" + b).orElse("");
            return Optional.of(new OutOfScope(
                    Kind.PERIOD,
                    "질문의 연도 %s이 코퍼스 범위(%d~%d) 밖입니다".formatted(asked, EARLIEST_YEAR, LATEST_YEAR),
                    "다루는 공시는 2023-01-01 ~ 2026-03-31 접수분이고, 그 안에 담긴 재무 수치는 "
                            + "%d년치까지입니다.".formatted(EARLIEST_YEAR)));
        }
        return Optional.empty();
    }

    private static boolean inRange(int year) {
        return year >= EARLIEST_YEAR && year <= LATEST_YEAR;
    }

    /** 연도가 하나라도 범위 안이면 막지 않는다. 그 해는 답할 수 있다. */
    private static List<Integer> yearsIn(String question) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = YEAR.matcher(question);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group().replaceAll("[^0-9]", ""));
            if (!years.contains(value)) {
                years.add(value);
            }
        }
        return years;
    }

    /**
     * @param kind       사유 갈래
     * @param thinkTrace 처리 과정에 남길 문장
     * @param answerNote 답변에 덧붙일 문장
     */
    public record OutOfScope(Kind kind, String thinkTrace, String answerNote) {
    }
}

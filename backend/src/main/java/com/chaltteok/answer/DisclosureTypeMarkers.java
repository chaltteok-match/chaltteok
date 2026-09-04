package com.chaltteok.answer;

import java.util.List;

/**
 * 질문이 어떤 공시 유형을 못 박는지 알려 주는 표현들.
 *
 * <p>{@link AnswerHandlerSelector}는 {@code supports()}가 처음 참인 Handler를 집는다.
 * 질문이 유형 두 개를 가리키면 더 구체적인 쪽이 아니라 {@code @Order}가 앞선 쪽이 가져간다.
 * {@code 유상증자로 조달한 자금 중 시설투자에 쓰는 금액}은 유상증자 질문인데,
 * 거래소공시가 {@code 시설투자}를 주제 키워드로 들고 있고 순위가 앞서서 가져갔다.
 *
 * <p>그래서 유형을 못 박는 표현이 질문에 있으면 그 유형이 아닌 Handler가 물러난다.
 * 라우팅이 어긋나도 예외가 나지 않고 엉뚱한 Handler의 답이 그대로 나가기 때문에,
 * 순위에 기대지 않고 표현으로 끊는다.
 *
 * <p>정기공시 항목({@code 매출액}·{@code 영업이익})은 표지가 아니다. 여러 유형의 문서에 함께
 * 나오는 말이라 유형을 못 박지 못한다. {@code 계약금액이 최근 매출액의 몇 퍼센트인가}는
 * 거래소공시가 답하는 것이 맞다.
 */
public final class DisclosureTypeMarkers {

    /** 주요사항보고서 유상증자결정. */
    public static final List<String> FUNDRAISING = List.of("유상증자");

    /** 주요사항보고서 자기주식취득결정. 처분·신탁도 자기주식 질문이라 함께 둔다. */
    public static final List<String> TREASURY = List.of("자기주식");

    /** 지분공시 대량보유상황보고서. */
    public static final List<String> OWNERSHIP =
            List.of("대량보유", "대량 보유", "지분공시", "보유상황");

    private DisclosureTypeMarkers() {
    }

    /** 주어진 표지 묶음 중 하나라도 질문에 있으면 참. */
    public static boolean mentions(String question, List<String> markers) {
        if (question == null) {
            return false;
        }
        return markers.stream().anyMatch(question::contains);
    }

    /** 주어진 묶음들 중 하나라도 질문에 있으면 참. 자기 유형은 넘기지 않는다. */
    @SafeVarargs
    public static boolean mentionsAnyOf(String question, List<String>... markerGroups) {
        for (List<String> markers : markerGroups) {
            if (mentions(question, markers)) {
                return true;
            }
        }
        return false;
    }
}

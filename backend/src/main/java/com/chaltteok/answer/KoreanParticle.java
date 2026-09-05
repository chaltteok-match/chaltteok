package com.chaltteok.answer;

/**
 * 앞 글자의 받침에 따라 한국어 조사를 고른다.
 *
 * <p>항목 이름을 문장에 끼워 넣을 때 조사를 고정하면 '계약금액가'처럼 틀린 문장이 나온다.
 * 답변 불가 사유는 사용자가 그대로 읽는 문장이라 조사를 맞춘다.
 */
public final class KoreanParticle {

    private static final char HANGUL_FIRST = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;
    private static final int FINAL_CONSONANT_COUNT = 28;

    private KoreanParticle() {
    }

    /** 이/가 */
    public static String subject(String word) {
        return hasFinalConsonant(word) ? "이" : "가";
    }

    /** 을/를 */
    public static String object(String word) {
        return hasFinalConsonant(word) ? "을" : "를";
    }

    /** 와/과. 기업명을 여러 개 이어 쓸 때 쓴다. */
    public static String and(String word) {
        return hasFinalConsonant(word) ? "과" : "와";
    }

    /** 은/는 */
    public static String topic(String word) {
        return hasFinalConsonant(word) ? "은" : "는";
    }

    /**
     * 마지막 글자에 받침이 있는지.
     *
     * <p>한글 음절이 아니면(영문·숫자·기호로 끝나면) 받침 여부를 알 수 없으므로
     * 받침이 있는 것으로 본다. '이'·'을'이 '가'·'를'보다 어색함이 덜하다.
     */
    private static boolean hasFinalConsonant(String word) {
        if (word == null || word.isEmpty()) {
            return true;
        }
        char last = word.charAt(word.length() - 1);
        if (last < HANGUL_FIRST || last > HANGUL_LAST) {
            return true;
        }
        return (last - HANGUL_FIRST) % FINAL_CONSONANT_COUNT != 0;
    }
}

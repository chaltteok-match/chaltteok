package com.chaltteok.bond;

import com.chaltteok.answer.CorpNameResolver;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 질문에서 기업·전환/교환 서식·항목을 읽는다.
 */
final class BondQuestionParser {

    static final String FACE_AMOUNT_FACT_KEY = "bond_face_amount";
    static final String COUPON_RATE_FACT_KEY = "bond_coupon_rate";
    static final String MATURITY_DATE_FACT_KEY = "bond_maturity_date";
    static final String EXERCISE_PRICE_FACT_KEY = "bond_exercise_price";
    static final String EXERCISE_SHARES_FACT_KEY = "bond_exercise_shares";

    private static final Map<String, String> ITEM_KEYWORDS = new LinkedHashMap<>();

    static {
        Map<String, String> raw = Map.ofEntries(
                Map.entry("권면총액", FACE_AMOUNT_FACT_KEY),
                Map.entry("권면 총액", FACE_AMOUNT_FACT_KEY),
                Map.entry("발행금액", FACE_AMOUNT_FACT_KEY),
                Map.entry("표면이자율", COUPON_RATE_FACT_KEY),
                Map.entry("표면이자", COUPON_RATE_FACT_KEY),
                Map.entry("사채만기일", MATURITY_DATE_FACT_KEY),
                Map.entry("만기일", MATURITY_DATE_FACT_KEY),
                Map.entry("전환가액", EXERCISE_PRICE_FACT_KEY),
                Map.entry("교환가액", EXERCISE_PRICE_FACT_KEY),
                Map.entry("주식 수", EXERCISE_SHARES_FACT_KEY),
                Map.entry("주식수", EXERCISE_SHARES_FACT_KEY)
        );
        raw.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length())
                        .reversed())
                .forEach(entry -> ITEM_KEYWORDS.put(entry.getKey(), entry.getValue()));
    }

    private BondQuestionParser() {
    }

    static BondQuestion parse(String question, CorpNameResolver corpNames) {
        String text = question == null ? "" : question;
        String corpName = corpNames.resolve(text);
        BondForm form = formOf(text);
        String item = ITEM_KEYWORDS.keySet().stream()
                .filter(text::contains)
                .findFirst()
                .orElse(null);
        String factKey = item == null ? null : ITEM_KEYWORDS.get(item);
        String itemLabel = item == null ? "사채 발행" : item;
        return new BondQuestion(corpName, form, factKey, itemLabel);
    }

    static BondForm formOf(String question) {
        String compact = question.replace(" ", "");
        if (compact.contains("교환사채")) {
            return BondForm.EXCHANGEABLE;
        }
        if (compact.contains("전환사채")) {
            return BondForm.CONVERTIBLE;
        }
        return null;
    }
}

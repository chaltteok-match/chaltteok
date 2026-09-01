package com.chaltteok.financial;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;

/**
 * 원 단위 정규화 값을 사람이 읽는 표기로 바꾼다. 예: {@code 300870903000000} → {@code 300조 8,709억원}.
 *
 * <p>표기는 읽기 위한 것이고, 검증에 쓰는 값은 언제나 정규화 원본이다.
 */
final class KoreanAmountFormatter {

    private static final BigDecimal JO = new BigDecimal("1000000000000");
    private static final BigDecimal EOK = new BigDecimal("100000000");

    private KoreanAmountFormatter() {
    }

    static String format(String normalizedKrw) {
        BigDecimal value = new BigDecimal(normalizedKrw);
        String sign = value.signum() < 0 ? "-" : "";
        value = value.abs();

        BigInteger jo = value.divideToIntegralValue(JO).toBigInteger();
        BigDecimal afterJo = value.subtract(new BigDecimal(jo).multiply(JO));
        BigInteger eok = afterJo.divideToIntegralValue(EOK).toBigInteger();

        DecimalFormat comma = new DecimalFormat("#,##0");
        StringBuilder text = new StringBuilder(sign);
        if (jo.signum() > 0) {
            text.append(comma.format(jo)).append("조");
        }
        if (eok.signum() > 0) {
            if (jo.signum() > 0) {
                text.append(' ');
            }
            text.append(comma.format(eok)).append("억");
        }
        if (jo.signum() == 0 && eok.signum() == 0) {
            return sign + comma.format(value) + "원";
        }
        return text.append("원").toString();
    }
}

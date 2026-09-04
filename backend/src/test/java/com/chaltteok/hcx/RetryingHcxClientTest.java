package com.chaltteok.hcx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 실제 API를 부르지 않는다. 대기도 하지 않고 기록만 한다.
 */
class RetryingHcxClientTest {

    private static final HcxPrompt PROMPT = new HcxPrompt("지시", "질문");
    private static final HcxResult OK =
            new HcxResult("답변", "HCX-005", 100, 50, 150);

    private final List<Duration> slept = new ArrayList<>();

    /** 정해 둔 결과를 순서대로 돌려주는 가짜 클라이언트. */
    private static final class FakeClient implements HcxClient {
        private final Deque<Object> outcomes = new ArrayDeque<>();
        private int calls;

        FakeClient(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public HcxResult generate(HcxPrompt prompt) {
            calls++;
            Object outcome = outcomes.isEmpty() ? OK : outcomes.poll();
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (HcxResult) outcome;
        }
    }

    private RetryingHcxClient client(HcxClient delegate, int maxAttempts) {
        return client(delegate, maxAttempts, Duration.ofSeconds(120));
    }

    private RetryingHcxClient client(HcxClient delegate, int maxAttempts, Duration maxElapsed) {
        return new RetryingHcxClient(
                delegate, maxAttempts,
                Duration.ofMillis(1_000), 2.0, Duration.ofMillis(8_000),
                maxElapsed,
                slept::add);
    }

    private static HcxClientException failure(HcxErrorType type) {
        return new HcxClientException(type, "실패");
    }

    @Test
    void 첫_호출이_성공하면_다시_걸지_않는다() {
        FakeClient delegate = new FakeClient(OK);

        assertThat(client(delegate, 3).generate(PROMPT)).isEqualTo(OK);
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = HcxErrorType.class, names = {"RATE_LIMIT", "TIMEOUT", "UPSTREAM"})
    void 다시_걸어_볼_만한_실패는_재시도한다(HcxErrorType type) {
        FakeClient delegate = new FakeClient(failure(type), OK);

        assertThat(client(delegate, 3).generate(PROMPT)).isEqualTo(OK);
        assertThat(delegate.calls).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = HcxErrorType.class,
            names = {"AUTHENTICATION", "CONFIGURATION", "INVALID_RESPONSE"})
    void 다시_걸어도_같은_실패는_즉시_끝낸다(HcxErrorType type) {
        FakeClient delegate = new FakeClient(failure(type), OK);

        assertThatThrownBy(() -> client(delegate, 3).generate(PROMPT))
                .isInstanceOf(HcxClientException.class);
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void 대기_시간을_지수로_늘린다() {
        FakeClient delegate = new FakeClient(
                failure(HcxErrorType.RATE_LIMIT),
                failure(HcxErrorType.RATE_LIMIT),
                failure(HcxErrorType.RATE_LIMIT),
                OK);

        assertThat(client(delegate, 4).generate(PROMPT)).isEqualTo(OK);
        assertThat(slept).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void 대기_시간에_상한을_둔다() {
        FakeClient delegate = new FakeClient(
                failure(HcxErrorType.UPSTREAM), failure(HcxErrorType.UPSTREAM),
                failure(HcxErrorType.UPSTREAM), failure(HcxErrorType.UPSTREAM),
                failure(HcxErrorType.UPSTREAM), OK);

        assertThat(client(delegate, 6).generate(PROMPT)).isEqualTo(OK);
        assertThat(slept).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(8), Duration.ofSeconds(8));
    }

    @Test
    void 서버가_알려_준_대기_시간을_따른다() {
        HcxClientException rateLimited = new HcxClientException(
                HcxErrorType.RATE_LIMIT, "429", Duration.ofSeconds(5), null);
        FakeClient delegate = new FakeClient(rateLimited, OK);

        assertThat(client(delegate, 3).generate(PROMPT)).isEqualTo(OK);
        assertThat(slept).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void 재시도_예산을_넘길_대기는_하지_않는다() {
        HcxClientException rateLimited = new HcxClientException(
                HcxErrorType.RATE_LIMIT, "429", Duration.ofSeconds(200), null);
        FakeClient delegate = new FakeClient(rateLimited, OK);

        assertThatThrownBy(() -> client(delegate, 3, Duration.ofSeconds(120)).generate(PROMPT))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(HcxErrorType.RATE_LIMIT);
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(slept).isEmpty();
    }

    @Test
    void 횟수를_다_쓰면_마지막_실패를_그대로_올린다() {
        FakeClient delegate = new FakeClient(
                failure(HcxErrorType.RATE_LIMIT),
                failure(HcxErrorType.RATE_LIMIT),
                failure(HcxErrorType.UPSTREAM));

        assertThatThrownBy(() -> client(delegate, 3).generate(PROMPT))
                .isInstanceOf(HcxClientException.class)
                .extracting(exception -> ((HcxClientException) exception).getErrorType())
                .isEqualTo(HcxErrorType.UPSTREAM);
        assertThat(delegate.calls).isEqualTo(3);
        assertThat(slept).hasSize(2);
    }

    @Test
    void 최대_시도를_1로_두면_재시도하지_않는다() {
        FakeClient delegate = new FakeClient(failure(HcxErrorType.RATE_LIMIT), OK);

        assertThatThrownBy(() -> client(delegate, 1).generate(PROMPT))
                .isInstanceOf(HcxClientException.class);
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void 최악의_경우에도_평가_타임아웃_안에_든다() {
        // 시도 3회 × 호출 타임아웃 30초 + 백오프 대기(1s + 2s) = 93초 < 300초
        long worstCall = 3L * 30;
        long worstWait = 1 + 2;
        assertThat(worstCall + worstWait).isLessThan(300);
    }
}

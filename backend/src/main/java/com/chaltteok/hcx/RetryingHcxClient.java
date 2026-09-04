package com.chaltteok.hcx;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 다시 걸어 볼 만한 실패에 한해 재시도하는 {@link HcxClient} 데코레이터.
 *
 * <p>근거는 이미 손에 있는데 문장을 만드는 단계 하나 때문에 질문 전체를 잃지 않기 위해서다.
 * 평가 서버 응답 타임아웃이 300초인데 우리 호출 타임아웃은 30초라 재시도에 쓸 여유가 있다.
 *
 * <p>인증·설정 오류는 다시 걸어도 같은 결과라 즉시 실패한다
 * ({@link HcxClientException#isRetryable()}).
 *
 * <p>대기 시간은 지수 백오프로 늘리되, 서버가 {@code Retry-After}로 알려 준 값이 있으면
 * 그 값을 따른다. 남은 예산({@code retryMaxElapsed})을 넘길 대기는 하지 않고 바로 실패한다.
 * 예산을 두지 않으면 서버가 큰 {@code Retry-After}를 줬을 때 평가 타임아웃을 넘길 수 있다.
 */
final class RetryingHcxClient implements HcxClient {

    private static final Logger log = LoggerFactory.getLogger(RetryingHcxClient.class);

    /** 테스트에서 실제로 자지 않기 위해 분리한다. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HcxClient delegate;
    private final int maxAttempts;
    private final Duration initialInterval;
    private final double multiplier;
    private final Duration maxInterval;
    private final Duration maxElapsed;
    private final Sleeper sleeper;

    RetryingHcxClient(
            HcxClient delegate,
            int maxAttempts,
            Duration initialInterval,
            double multiplier,
            Duration maxInterval,
            Duration maxElapsed,
            Sleeper sleeper
    ) {
        this.delegate = delegate;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialInterval = initialInterval;
        this.multiplier = multiplier;
        this.maxInterval = maxInterval;
        this.maxElapsed = maxElapsed;
        this.sleeper = sleeper;
    }

    @Override
    public HcxResult generate(HcxPrompt prompt) {
        Duration waited = Duration.ZERO;
        Duration interval = initialInterval;

        for (int attempt = 1; ; attempt++) {
            try {
                return delegate.generate(prompt);
            } catch (HcxClientException exception) {
                if (!exception.isRetryable() || attempt >= maxAttempts) {
                    throw exception;
                }
                Duration wait = waitFor(exception, interval);
                if (waited.plus(wait).compareTo(maxElapsed) > 0) {
                    log.warn("HyperCLOVA X 재시도 예산을 넘겨 중단합니다. "
                                    + "type={} attempt={}/{} 대기예정={}s 누적={}s 예산={}s",
                            exception.getErrorType(), attempt, maxAttempts,
                            wait.toSeconds(), waited.toSeconds(), maxElapsed.toSeconds());
                    throw exception;
                }
                log.warn("HyperCLOVA X 호출 실패로 재시도합니다. type={} attempt={}/{} 대기={}ms",
                        exception.getErrorType(), attempt, maxAttempts, wait.toMillis());
                sleepQuietly(wait);
                waited = waited.plus(wait);
                interval = nextInterval(interval);
            }
        }
    }

    /** 서버가 알려 준 값이 있으면 그것을 쓴다. 없으면 지수 백오프 값을 쓴다. */
    private Duration waitFor(HcxClientException exception, Duration interval) {
        Duration retryAfter = exception.getRetryAfter();
        return retryAfter != null && !retryAfter.isNegative() ? retryAfter : interval;
    }

    private Duration nextInterval(Duration interval) {
        Duration next = Duration.ofMillis((long) (interval.toMillis() * multiplier));
        return next.compareTo(maxInterval) > 0 ? maxInterval : next;
    }

    private void sleepQuietly(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HcxClientException(
                    HcxErrorType.TIMEOUT,
                    "HyperCLOVA X 재시도 대기 중 중단되었습니다.",
                    interrupted);
        }
    }
}

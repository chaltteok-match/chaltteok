package com.chaltteok.hcx;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "hcx")
public class HcxProperties {

    private boolean enabled;

    @NotBlank
    private String baseUrl = "https://clovastudio.stream.ntruss.com/v1/openai";

    @NotBlank
    private String completionsPath = "/chat/completions";

    private String apiKey = "";

    @NotBlank
    private String model = "HCX-005";

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double temperature = 0.1;

    /**
     * 출력 상한. TPM이 실제 소비가 아니라 {@code input token + maxTokens}로 계산되므로
     * 낮출수록 429에 덜 닿는다. 코퍼스에서 답이 가장 긴 경우(현대건설 14회 정정 사슬)가
     * 377토큰이었고, 같은 질문을 두 번 불렀을 때 최대 1.6배까지 흔들렸다.
     * 잘린 답은 틀린 답이므로 최댓값의 두 배로 둔다. 근거는 backend/README.md 참조.
     */
    @Min(1)
    private int maxTokens = 768;

    @Min(1)
    private int timeoutSeconds = 30;

    /**
     * 한 질문에 HCX를 부르는 최대 횟수(첫 호출 포함). 1이면 재시도하지 않는다.
     * 429·타임아웃·5xx에만 다시 건다.
     */
    @Min(1)
    private int retryMaxAttempts = 3;

    /** 첫 재시도까지 기다리는 시간(ms). 이후 {@code retryMultiplier}만큼 늘어난다. */
    @Min(0)
    private long retryInitialIntervalMillis = 1_000;

    @DecimalMin("1.0")
    private double retryMultiplier = 2.0;

    @Min(0)
    private long retryMaxIntervalMillis = 8_000;

    /**
     * 재시도 대기에 쓸 수 있는 총 시간(초). 평가 서버 응답 타임아웃이 300초라
     * 호출 자체에 쓰는 시간을 빼고도 남도록 넉넉히 잡는다.
     * 서버가 큰 {@code Retry-After}를 줘도 이 예산을 넘기면 기다리지 않고 실패한다.
     */
    @Min(0)
    private long retryMaxElapsedSeconds = 120;

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryInitialIntervalMillis() {
        return retryInitialIntervalMillis;
    }

    public void setRetryInitialIntervalMillis(long retryInitialIntervalMillis) {
        this.retryInitialIntervalMillis = retryInitialIntervalMillis;
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
    }

    public long getRetryMaxIntervalMillis() {
        return retryMaxIntervalMillis;
    }

    public void setRetryMaxIntervalMillis(long retryMaxIntervalMillis) {
        this.retryMaxIntervalMillis = retryMaxIntervalMillis;
    }

    public long getRetryMaxElapsedSeconds() {
        return retryMaxElapsedSeconds;
    }

    public void setRetryMaxElapsedSeconds(long retryMaxElapsedSeconds) {
        this.retryMaxElapsedSeconds = retryMaxElapsedSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCompletionsPath() {
        return completionsPath;
    }

    public void setCompletionsPath(String completionsPath) {
        this.completionsPath = completionsPath;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}

package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [Sim] fault=...
 * - 실제 적용은 ScenarioRunner에서 수행
 */
public final class FaultStep implements ScenarioStep {

    public enum Type {
        DELAY,
        FRAGMENT,
        DROP,
        CORRUPT,
        DISCONNECT,
        CLEAR
    }

    public enum ScopeMode {
        DURATION, // duration=30s
        NEXT      // next count=20
    }

    private final Type type;
    private final ScopeMode scopeMode;

    // 공통 scope
    private final Long durationMs; // DURATION
    private final Integer nextCount; // NEXT

    // delay
    private final Long delayMs;
    private final Long jitterMs;

    // fragment
    private final Integer minParts;
    private final Integer maxParts;

    // drop/corrupt
    private final Double rate;
    private final Boolean protectFraming;

    // disconnect
    private final Long afterMs;
    private final Long downMs; // 현재는 close만 수행, down은 로그만

    public FaultStep(
            Type type,
            ScopeMode scopeMode,
            Long durationMs,
            Integer nextCount,
            Long delayMs,
            Long jitterMs,
            Integer minParts,
            Integer maxParts,
            Double rate,
            Boolean protectFraming,
            Long afterMs,
            Long downMs
    ) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.scopeMode = Objects.requireNonNull(scopeMode, "scopeMode must not be null");
        this.durationMs = durationMs;
        this.nextCount = nextCount;

        this.delayMs = delayMs;
        this.jitterMs = jitterMs;

        this.minParts = minParts;
        this.maxParts = maxParts;

        this.rate = rate;
        this.protectFraming = protectFraming;

        this.afterMs = afterMs;
        this.downMs = downMs;
    }

    public Type getType() { return type; }

    public ScopeMode getScopeMode() { return scopeMode; }

    public Long getDurationMs() { return durationMs; }

    public Integer getNextCount() { return nextCount; }

    public Long getDelayMs() { return delayMs; }

    public Long getJitterMs() { return jitterMs; }

    public Integer getMinParts() { return minParts; }

    public Integer getMaxParts() { return maxParts; }

    public Double getRate() { return rate; }

    public Boolean getProtectFraming() { return protectFraming; }

    public Long getAfterMs() { return afterMs; }

    public Long getDownMs() { return downMs; }
}
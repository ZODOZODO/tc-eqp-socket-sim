package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [EqpToTc] every=1s count=60 <payload...>
 * [EqpToTc] window=10s count=2 <payload...>
 */
public final class EmitStep implements ScenarioStep {

    public enum Mode { INTERVAL, WINDOW }

    private final Mode mode;
    private final long intervalOrWindowMs;
    private final Count count;
    private final String payloadTemplate;
    private final Long jitterMs; // interval only (optional)

    public EmitStep(Mode mode, long intervalOrWindowMs, Count count, String payloadTemplate, Long jitterMs) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        if (intervalOrWindowMs <= 0) {
            throw new IllegalArgumentException("interval/window must be > 0");
        }
        this.intervalOrWindowMs = intervalOrWindowMs;
        this.count = Objects.requireNonNull(count, "count must not be null");
        this.payloadTemplate = Objects.requireNonNull(payloadTemplate, "payloadTemplate must not be null");
        this.jitterMs = jitterMs;
    }

    public Mode getMode() {
        return mode;
    }

    public long getIntervalOrWindowMs() {
        return intervalOrWindowMs;
    }

    public Count getCount() {
        return count;
    }

    public String getPayloadTemplate() {
        return payloadTemplate;
    }

    public Long getJitterMs() {
        return jitterMs;
    }

    public sealed interface Count permits CountFixed, CountForever {}

    public static final class CountFixed implements Count {
        private final int value;

        public CountFixed(int value) {
            if (value <= 0) throw new IllegalArgumentException("count must be > 0");
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    public static final class CountForever implements Count {
        public static final CountForever INSTANCE = new CountForever();
        private CountForever() {}
    }
}
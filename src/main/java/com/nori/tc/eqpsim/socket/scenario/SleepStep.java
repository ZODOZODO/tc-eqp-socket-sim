package com.nori.tc.eqpsim.socket.scenario;

/**
 * [Sim] sleep=500ms
 */
public final class SleepStep implements ScenarioStep {

    private final long sleepMs;

    public SleepStep(long sleepMs) {
        if (sleepMs < 0) throw new IllegalArgumentException("sleepMs must be >= 0");
        this.sleepMs = sleepMs;
    }

    public long getSleepMs() {
        return sleepMs;
    }
}
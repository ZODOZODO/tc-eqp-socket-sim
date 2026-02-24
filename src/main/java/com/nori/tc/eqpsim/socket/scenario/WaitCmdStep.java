package com.nori.tc.eqpsim.socket.scenario;

import java.util.Objects;

/**
 * [TcToEqp] CMD=XXXX
 * - expectedCmdUpper: 대문자 비교용
 * - timeoutOverrideSec: null이면 EQP 기본 waitTimeoutSec 사용
 */
public final class WaitCmdStep implements ScenarioStep {

    private final String expectedCmdUpper;
    private final Long timeoutOverrideSec;

    public WaitCmdStep(String expectedCmdUpper, Long timeoutOverrideSec) {
        this.expectedCmdUpper = Objects.requireNonNull(expectedCmdUpper, "expectedCmdUpper must not be null");
        this.timeoutOverrideSec = timeoutOverrideSec;
    }

    public String getExpectedCmdUpper() {
        return expectedCmdUpper;
    }

    public Long getTimeoutOverrideSec() {
        return timeoutOverrideSec;
    }
}
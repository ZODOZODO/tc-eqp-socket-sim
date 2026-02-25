package com.nori.tc.eqpsim.socket.lifecycle;

/**
 * ScenarioCompletionTracker
 *
 * 목적:
 * - 시나리오 완료/패시브 연결 open/close 이벤트를 추적하여
 *   "모든 EQP 완료 + PASSIVE는 TC가 끊어서 open=0" 조건일 때 프로세스를 종료한다.
 *
 * 주의:
 * - 테스트/단순 실행을 위해 NOOP 구현을 제공한다.
 */
public interface ScenarioCompletionTracker {

    ScenarioCompletionTracker NOOP = new ScenarioCompletionTracker() {
        @Override public void markScenarioCompleted(String eqpId) {}
        @Override public void markPassiveChannelOpened(String eqpId) {}
        @Override public void markPassiveChannelClosed(String eqpId) {}
    };

    void markScenarioCompleted(String eqpId);

    /**
     * PASSIVE는 시나리오가 끝나도 연결을 유지해야 하므로,
     * 핸드셰이크 완료 시점에 open 집합에 등록한다.
     */
    void markPassiveChannelOpened(String eqpId);

    /**
     * PASSIVE 연결 종료는 TC가 수행한다.
     * channelInactive 시점에 open 집합에서 제거한다.
     */
    void markPassiveChannelClosed(String eqpId);
}
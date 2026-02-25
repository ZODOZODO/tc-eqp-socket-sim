package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.util.AttributeKey;

/**
 * Netty Channel Attribute Keys
 */
public final class ChannelAttributes {

    private ChannelAttributes() {
        // utility class
    }

    public static final AttributeKey<String> ENDPOINT_ID = AttributeKey.valueOf("tc.eqpsim.endpointId");
    public static final AttributeKey<EqpRuntime> EQP = AttributeKey.valueOf("tc.eqpsim.eqp");

    /**
     * 채널 단위 장애 주입 상태
     */
    public static final AttributeKey<FaultState> FAULT_STATE = AttributeKey.valueOf("tc.eqpsim.faultState");

    /**
     * 채널 종료 사유(정상/비정상)
     * - ScenarioRunner가 정상 완료 후 close 시 값을 넣는다.
     * - ActiveClientConnector는 이 값이 SCENARIO_COMPLETED면 재연결하지 않는다.
     */
    public static final AttributeKey<String> CLOSE_REASON = AttributeKey.valueOf("tc.eqpsim.closeReason");

    /**
     * 시나리오 정상 완료로 인한 정상 종료
     */
    public static final String CLOSE_REASON_SCENARIO_COMPLETED = "SCENARIO_COMPLETED";
}
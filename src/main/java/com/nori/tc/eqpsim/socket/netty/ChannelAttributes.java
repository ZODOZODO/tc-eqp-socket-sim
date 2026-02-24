package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.util.AttributeKey;

/**
 * Netty Channel Attribute Keys
 *
 * - ENDPOINT_ID: listen/connect endpoint id
 * - EQP: 바인딩된 가상 설비 런타임 정의(EqpRuntime)
 * - FAULT_STATE: 채널 단위 장애/스트레스 주입 상태(전역 Outbound 훅에서 사용)
 */
public final class ChannelAttributes {

    private ChannelAttributes() {
        // utility class
    }

    public static final AttributeKey<String> ENDPOINT_ID = AttributeKey.valueOf("tc.eqpsim.endpointId");
    public static final AttributeKey<EqpRuntime> EQP = AttributeKey.valueOf("tc.eqpsim.eqp");

    /**
     * 전역 장애 상태(채널 단위)
     * - Handshake/Scenario 등 "모든 outbound"에서 공통 사용
     */
    public static final AttributeKey<FaultState> FAULT_STATE = AttributeKey.valueOf("tc.eqpsim.faultState");
}
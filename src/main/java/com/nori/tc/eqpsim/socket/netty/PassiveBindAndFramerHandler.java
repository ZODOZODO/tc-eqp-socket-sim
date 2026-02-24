package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.SocketFramerFactory;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PASSIVE 연결:
 * - EQP reserve
 * - framer 설치
 * - handshake 설치(성공 시 runner로 교체)
 * - lifecycle 설치(채널 종료 시 EQP 반환)
 * - fault state 초기화(전역 Outbound 훅 사용)
 */
public class PassiveBindAndFramerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PassiveBindAndFramerHandler.class);

    private final String listenEndpointId;
    private final EqpRuntimeRegistry registry;
    private final ScenarioRegistry scenarioRegistry;

    public PassiveBindAndFramerHandler(String listenEndpointId, EqpRuntimeRegistry registry, ScenarioRegistry scenarioRegistry) {
        this.listenEndpointId = listenEndpointId;
        this.registry = registry;
        this.scenarioRegistry = scenarioRegistry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String eqpId = registry.reservePassiveEqpId(listenEndpointId);
        if (eqpId == null) {
            log.warn("PASSIVE accept but no available EQP in endpoint pool. endpointId={}", listenEndpointId);
            ctx.close();
            return;
        }

        EqpRuntime eqp = registry.getEqp(eqpId);
        if (eqp == null) {
            log.error("Reserved EQP not found in registry. eqpId={}", eqpId);
            ctx.close();
            return;
        }

        ctx.channel().attr(ChannelAttributes.ENDPOINT_ID).set(listenEndpointId);
        ctx.channel().attr(ChannelAttributes.EQP).set(eqp);

        // ✅ 전역 fault 상태 초기화 (채널 단위)
        ctx.channel().attr(ChannelAttributes.FAULT_STATE).set(new FaultState());

        ByteToMessageDecoder framer = SocketFramerFactory.create(eqp.getSocketType());

        ctx.pipeline().addFirst("framer", framer);
        ctx.pipeline().addAfter("framer", "handshake", new HandshakeHandler(scenarioRegistry));
        ctx.pipeline().addLast("eqpLifecycle", new EqpLifecycleHandler(registry));

        ctx.pipeline().remove(this);

        log.info("PASSIVE channel prepared. endpointId={}, eqpId={}, socketTypeKind={}",
                listenEndpointId, eqp.getEqpId(), eqp.getSocketType().getKind());

        ctx.fireChannelActive();
    }
}
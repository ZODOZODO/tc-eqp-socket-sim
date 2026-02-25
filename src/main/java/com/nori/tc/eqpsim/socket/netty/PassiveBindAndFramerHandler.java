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
 * - rawRx(디버그) -> framer -> handshake -> lifecycle
 */
public class PassiveBindAndFramerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PassiveBindAndFramerHandler.class);

    private final String passiveEndpointId;
    private final EqpRuntimeRegistry registry;
    private final ScenarioRegistry scenarioRegistry;

    public PassiveBindAndFramerHandler(String passiveEndpointId, EqpRuntimeRegistry registry, ScenarioRegistry scenarioRegistry) {
        this.passiveEndpointId = passiveEndpointId;
        this.registry = registry;
        this.scenarioRegistry = scenarioRegistry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String eqpId = registry.reservePassiveEqpId(passiveEndpointId);
        if (eqpId == null) {
            log.warn("PASSIVE accept but no available EQP in endpoint pool. endpointId={}", passiveEndpointId);
            ctx.close();
            return;
        }

        EqpRuntime eqp = registry.getEqp(eqpId);
        if (eqp == null) {
            log.error("Reserved EQP not found in registry. eqpId={}", eqpId);
            ctx.close();
            return;
        }

        ctx.channel().attr(ChannelAttributes.ENDPOINT_ID).set(passiveEndpointId);
        ctx.channel().attr(ChannelAttributes.EQP).set(eqp);
        ctx.channel().attr(ChannelAttributes.FAULT_STATE).set(new FaultState());

        // ✅ raw bytes 로깅을 framer 앞단에 추가
        ctx.pipeline().addFirst("rawRx", new RawInboundBytesLoggingHandler(5));

        ByteToMessageDecoder framer = SocketFramerFactory.create(eqp.getSocketType());
        ctx.pipeline().addAfter("rawRx", "framer", framer);
        ctx.pipeline().addAfter("framer", "handshake", new HandshakeHandler(scenarioRegistry));
        ctx.pipeline().addLast("eqpLifecycle", new EqpLifecycleHandler(registry));

        ctx.pipeline().remove(this);

        ctx.fireChannelActive();
    }
}
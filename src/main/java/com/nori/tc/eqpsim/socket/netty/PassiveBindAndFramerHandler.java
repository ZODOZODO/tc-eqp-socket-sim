package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.SocketFramerFactory;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
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
 * PassiveBindAndFramerHandler
 *
 * 역할:
 * - TC accept 시 PASSIVE EQP pool에서 eqpId를 1개 할당하고,
 *   채널 pipeline에 rawRx / framer / handshake / eqpLifecycle 핸들러를 동적으로 추가한다.
 *
 * ✅ [B1 수정] pipeline 순서 보정
 * - 기존 addFirst("rawRx") → connLimit 앞에 삽입되는 문제 발생
 * - 수정: addAfter(selfName, ...) 역순 삽입으로 정확한 순서 보장
 *
 * 올바른 pipeline 순서:
 *   connLimit → rawRx → framer → handshake → eqpLifecycle
 *
 * 역순 삽입 원리:
 *   각 addAfter(selfName, ...) 호출이 selfName(passiveBind) 바로 뒤에 삽입되므로,
 *   eqpLifecycle → handshake → framer → rawRx 순서로 삽입하면
 *   최종적으로 rawRx → framer → handshake → eqpLifecycle 순서가 된다.
 */
public class PassiveBindAndFramerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(PassiveBindAndFramerHandler.class);

    private final String passiveEndpointId;
    private final EqpRuntimeRegistry registry;
    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioCompletionTracker tracker;

    public PassiveBindAndFramerHandler(String passiveEndpointId,
                                       EqpRuntimeRegistry registry,
                                       ScenarioRegistry scenarioRegistry) {
        this(passiveEndpointId, registry, scenarioRegistry, ScenarioCompletionTracker.NOOP);
    }

    public PassiveBindAndFramerHandler(String passiveEndpointId,
                                       EqpRuntimeRegistry registry,
                                       ScenarioRegistry scenarioRegistry,
                                       ScenarioCompletionTracker tracker) {
        this.passiveEndpointId = passiveEndpointId;
        this.registry = registry;
        this.scenarioRegistry = scenarioRegistry;
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // ─── PASSIVE EQP 할당 ────────────────────────────────────────────────────
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

        // ─── 채널 Attribute 세팅 ─────────────────────────────────────────────────
        ctx.channel().attr(ChannelAttributes.ENDPOINT_ID).set(passiveEndpointId);
        ctx.channel().attr(ChannelAttributes.EQP).set(eqp);
        ctx.channel().attr(ChannelAttributes.FAULT_STATE).set(new FaultState());

        // ─── [B1 수정] 역순 addAfter(selfName) 삽입 ─────────────────────────────
        // 목표 순서: connLimit → rawRx → framer → handshake → eqpLifecycle
        // 역순으로 삽입하면 selfName(passiveBind) 뒤에 올바른 순서로 쌓인다.
        //
        // 1) eqpLifecycle 삽입: ... passiveBind → eqpLifecycle
        // 2) handshake 삽입:    ... passiveBind → handshake → eqpLifecycle
        // 3) framer 삽입:       ... passiveBind → framer → handshake → eqpLifecycle
        // 4) rawRx 삽입:        ... passiveBind → rawRx → framer → handshake → eqpLifecycle
        // 5) passiveBind 제거:  connLimit → rawRx → framer → handshake → eqpLifecycle ✅

        String selfName = ctx.name();

        ctx.pipeline().addAfter(selfName, "eqpLifecycle",
                new EqpLifecycleHandler(registry, tracker));

        ctx.pipeline().addAfter(selfName, "handshake",
                new HandshakeHandler(scenarioRegistry, tracker));

        ByteToMessageDecoder framer = SocketFramerFactory.create(eqp.getSocketType());
        ctx.pipeline().addAfter(selfName, "framer", framer);

        ctx.pipeline().addAfter(selfName, "rawRx",
                new RawInboundBytesLoggingHandler(5));

        // passiveBind(this) 제거 후 channelActive 전파
        ctx.pipeline().remove(this);
        ctx.fireChannelActive();
    }
}
package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.SocketFramerFactory;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * ACTIVE(connect) 채널 초기화:
 * - rawRx(디버그) -> framer -> handshake
 */
public class ActiveChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final EqpRuntime eqp;
    private final ScenarioRegistry scenarioRegistry;

    public ActiveChannelInitializer(EqpRuntime eqp, ScenarioRegistry scenarioRegistry) {
        this.eqp = eqp;
        this.scenarioRegistry = scenarioRegistry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.attr(ChannelAttributes.ENDPOINT_ID).set(eqp.getEndpointId());
        ch.attr(ChannelAttributes.EQP).set(eqp);

        // 전역 fault 상태 초기화
        ch.attr(ChannelAttributes.FAULT_STATE).set(new FaultState());

        // ✅ framer 앞단 raw bytes 로깅
        ch.pipeline().addLast("rawRx", new RawInboundBytesLoggingHandler(5));

        ByteToMessageDecoder framer = SocketFramerFactory.create(eqp.getSocketType());
        ch.pipeline().addLast("framer", framer);

        ch.pipeline().addLast("handshake", new HandshakeHandler(scenarioRegistry));
    }
}
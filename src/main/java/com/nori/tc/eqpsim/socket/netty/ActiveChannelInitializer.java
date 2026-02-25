package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.SocketFramerFactory;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;

public class ActiveChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final EqpRuntime eqp;
    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioCompletionTracker tracker;

    public ActiveChannelInitializer(EqpRuntime eqp,
                                   ScenarioRegistry scenarioRegistry,
                                   ScenarioCompletionTracker tracker) {
        this.eqp = eqp;
        this.scenarioRegistry = scenarioRegistry;
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.attr(ChannelAttributes.ENDPOINT_ID).set(eqp.getEndpointId());
        ch.attr(ChannelAttributes.EQP).set(eqp);
        ch.attr(ChannelAttributes.FAULT_STATE).set(new FaultState());

        ch.pipeline().addLast("rawRx", new RawInboundBytesLoggingHandler(5));

        ByteToMessageDecoder framer = SocketFramerFactory.create(eqp.getSocketType());
        ch.pipeline().addLast("framer", framer);

        ch.pipeline().addLast("handshake", new HandshakeHandler(scenarioRegistry, tracker));
    }
}
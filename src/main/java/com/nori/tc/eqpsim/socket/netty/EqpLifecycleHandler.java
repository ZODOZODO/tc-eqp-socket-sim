package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

public class EqpLifecycleHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(EqpLifecycleHandler.class);

    private final EqpRuntimeRegistry registry;
    private final ScenarioCompletionTracker tracker;

    public EqpLifecycleHandler(EqpRuntimeRegistry registry) {
        this(registry, ScenarioCompletionTracker.NOOP);
    }

    public EqpLifecycleHandler(EqpRuntimeRegistry registry,
                              ScenarioCompletionTracker tracker) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
            String endpointId = ctx.channel().attr(ChannelAttributes.ENDPOINT_ID).get();

            if (eqp != null && eqp.getMode() == EqpProperties.Mode.PASSIVE) {
                registry.releasePassiveEqpId(endpointId, eqp.getEqpId());

                log.info(StructuredLog.event("passive_eqp_released",
                        "eqpId", eqp.getEqpId(),
                        "endpointId", endpointId,
                        "connId", ctx.channel().id().asShortText(),
                        "remote", String.valueOf(ctx.channel().remoteAddress()),
                        "local", String.valueOf(ctx.channel().localAddress())));

                // PASSIVE close는 TC가 수행 → channelInactive에서 closed로 마킹
                tracker.markPassiveChannelClosed(eqp.getEqpId());
            }
        } finally {
            ctx.fireChannelInactive();
        }
    }
}
package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * PASSIVE 채널 종료 시 EQP pool 반환.
 */
public class EqpLifecycleHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(EqpLifecycleHandler.class);

    private final EqpRuntimeRegistry registry;

    public EqpLifecycleHandler(EqpRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
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
            }
        } finally {
            ctx.fireChannelInactive();
        }
    }
}
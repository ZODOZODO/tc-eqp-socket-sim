package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.protocol.FrameTokenParser;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.ScenarioPlan;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import com.nori.tc.eqpsim.socket.scenario.runtime.ScenarioRunnerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final String CMD_INITIALIZE = "INITIALIZE";

    private final ScenarioRegistry scenarioRegistry;

    private volatile boolean handshaked = false;
    private ScheduledFuture<?> timeoutFuture;

    public HandshakeHandler(ScenarioRegistry scenarioRegistry) {
        super(true);
        this.scenarioRegistry = scenarioRegistry;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            log.error(StructuredLog.event("handshake_start_failed",
                    "reason", "eqp_attr_missing",
                    "connId", ctx.channel().id().asShortText()));
            ctx.close();
            return;
        }

        long configured = eqp.getHandshakeTimeoutSec();
        final long handshakeTimeoutSec = (configured > 0) ? configured : 60;

        timeoutFuture = ctx.executor().schedule(() -> {
            if (!handshaked) {
                log.warn(StructuredLog.event("handshake_timeout",
                        "eqpId", eqp.getEqpId(),
                        "mode", eqp.getMode(),
                        "endpointId", eqp.getEndpointId(),
                        "connId", ctx.channel().id().asShortText(),
                        "timeoutSec", handshakeTimeoutSec));
                ctx.close();
            }
        }, handshakeTimeoutSec, TimeUnit.SECONDS);

        log.info(StructuredLog.event("handshake_started",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText(),
                "timeoutSec", handshakeTimeoutSec,
                "remote", String.valueOf(ctx.channel().remoteAddress()),
                "local", String.valueOf(ctx.channel().localAddress())));

        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (handshaked) return;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            ctx.close();
            return;
        }

        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);

        // ✅ 핸드셰이크 RX 로그 (프레임이 내려오면 반드시 찍힘)
        log.info(StructuredLog.event("handshake_rx",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText(),
                "cmd", cmdUpper,
                "payload", frame));

        if (cmdUpper == null) return;
        if (!CMD_INITIALIZE.equals(cmdUpper)) return;

        String rep = "CMD=INITIALIZE_REP EQPID=" + eqp.getEqpId();

        // ✅ 핸드셰이크 TX 로그
        log.info(StructuredLog.event("handshake_tx",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText(),
                "payload", rep));

        OutboundFrameSender.send(ctx, eqp, rep);

        handshaked = true;
        cancelTimeout();

        log.info(StructuredLog.event("handshake_completed",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText()));

        ScenarioPlan plan = scenarioRegistry.getPlanByProfileId(eqp.getProfileId());
        if (plan == null) {
            log.error(StructuredLog.event("scenario_plan_missing",
                    "eqpId", eqp.getEqpId(),
                    "profileId", eqp.getProfileId(),
                    "connId", ctx.channel().id().asShortText()));
            ctx.close();
            return;
        }

        ctx.pipeline().replace(this, "runner", new ScenarioRunnerHandler(plan));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();

            log.warn(StructuredLog.event("handshake_channel_inactive",
                    "eqpId", eqp != null ? eqp.getEqpId() : "null",
                    "mode", eqp != null ? eqp.getMode() : "null",
                    "endpointId", eqp != null ? eqp.getEndpointId() : "null",
                    "connId", ctx.channel().id().asShortText(),
                    "handshaked", handshaked,
                    "remote", String.valueOf(ctx.channel().remoteAddress()),
                    "local", String.valueOf(ctx.channel().localAddress())));
        } finally {
            cancelTimeout();
            ctx.fireChannelInactive();
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> f = timeoutFuture;
        if (f != null) {
            f.cancel(false);
            timeoutFuture = null;
        }
    }
}
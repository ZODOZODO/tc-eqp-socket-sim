package com.nori.tc.eqpsim.socket.scenario.runtime;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.netty.ChannelAttributes;
import com.nori.tc.eqpsim.socket.netty.OutboundFrameSender;
import com.nori.tc.eqpsim.socket.protocol.FrameTokenParser;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ScenarioRunnerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerHandler.class);

    private static final long CLOSE_GRACE_MS = 100;

    private final ScenarioPlan plan;
    private final ScenarioCompletionTracker tracker;

    private volatile boolean started = false;
    private volatile boolean closeScheduled = false;

    private int index = 0;

    private WaitCmdStep waiting;
    private ScheduledFuture<?> waitTimeoutFuture;

    public ScenarioRunnerHandler(ScenarioPlan plan) {
        this(plan, ScenarioCompletionTracker.NOOP);
    }

    public ScenarioRunnerHandler(ScenarioPlan plan, ScenarioCompletionTracker tracker) {
        super(true);
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            ctx.executor().execute(() -> startIfNeeded(ctx, "handlerAdded"));
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        startIfNeeded(ctx, "channelActive");
        ctx.fireChannelActive();
    }

    private void startIfNeeded(ChannelHandlerContext ctx, String trigger) {
        if (started) return;
        started = true;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();

        log.info(StructuredLog.event("scenario_started",
                "trigger", trigger,
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "mode", eqp != null ? eqp.getMode() : "null",
                "endpointId", eqp != null ? eqp.getEndpointId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepCount", plan.getSteps().size()));

        advance(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (waiting == null) return;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();

        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);

        log.info(StructuredLog.event("scenario_rx_wait",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", index,
                "expectedCmd", waiting.getExpectedCmdUpper(),
                "cmd", cmdUpper,
                "payload", frame));

        if (cmdUpper == null) return;
        if (!waiting.getExpectedCmdUpper().equals(cmdUpper)) return;

        cancelWaitTimeout();
        waiting = null;

        log.info(StructuredLog.event("scenario_wait_matched",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", index,
                "matchedCmd", cmdUpper));

        index++;
        advance(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            cancelWaitTimeout();
        } finally {
            ctx.fireChannelInactive();
        }
    }

    private void advance(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            ctx.close();
            return;
        }

        while (true) {
            if (index >= plan.getSteps().size()) {
                log.info(StructuredLog.event("scenario_completed",
                        "eqpId", eqp.getEqpId(),
                        "mode", eqp.getMode(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile()));

                // ✅ 전역 완료 카운트
                tracker.markScenarioCompleted(eqp.getEqpId());

                // ✅ ACTIVE만 close
                if (eqp.getMode() == EqpProperties.Mode.ACTIVE) {
                    scheduleCloseAfterCompletion(ctx, eqp);
                } else {
                    log.info(StructuredLog.event("scenario_passive_keepalive",
                            "eqpId", eqp.getEqpId(),
                            "connId", ctx.channel().id().asShortText(),
                            "scenarioFile", plan.getSourceFile()));
                }
                return;
            }

            ScenarioStep st = plan.getSteps().get(index);

            if (st instanceof WaitCmdStep w) {
                waiting = w;

                long timeoutSec = (w.getTimeoutOverrideSec() != null) ? w.getTimeoutOverrideSec() : eqp.getWaitTimeoutSec();
                if (timeoutSec <= 0) timeoutSec = 60;

                final long t = timeoutSec;

                log.info(StructuredLog.event("scenario_wait_started",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "expectedCmd", w.getExpectedCmdUpper(),
                        "timeoutSec", t));

                waitTimeoutFuture = ctx.executor().schedule(() -> {
                    log.warn(StructuredLog.event("scenario_wait_timeout",
                            "eqpId", eqp.getEqpId(),
                            "connId", ctx.channel().id().asShortText(),
                            "scenarioFile", plan.getSourceFile(),
                            "stepIndex", index,
                            "expectedCmd", w.getExpectedCmdUpper(),
                            "timeoutSec", t));
                    ctx.close();
                }, timeoutSec, TimeUnit.SECONDS);

                return;
            }

            if (st instanceof SendStep s) {
                String resolved = ScenarioTemplateResolver.resolve(s.getPayloadTemplate(), eqp);
                OutboundFrameSender.send(ctx, eqp, resolved);

                log.info(StructuredLog.event("scenario_send",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "payload", resolved));

                index++;
                continue;
            }

            if (st instanceof EmitStep e) {
                log.info(StructuredLog.event("scenario_emit_started",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "mode", e.getMode(),
                        "intervalOrWindowMs", e.getIntervalOrWindowMs(),
                        "count", (e.getCount() instanceof EmitStep.CountForever) ? "forever" : ((EmitStep.CountFixed) e.getCount()).getValue(),
                        "jitterMs", e.getJitterMs() == null ? 0 : e.getJitterMs()));

                if (e.getCount() instanceof EmitStep.CountForever) {
                    startForeverEmit(ctx, eqp, e);
                    return;
                } else {
                    startFiniteEmitThenContinue(ctx, eqp, e);
                    return;
                }
            }

            if (st instanceof SleepStep sl) {
                long ms = sl.getSleepMs();
                log.info(StructuredLog.event("scenario_sleep",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "sleepMs", ms));

                ctx.executor().schedule(() -> {
                    index++;
                    advance(ctx);
                }, ms, TimeUnit.MILLISECONDS);
                return;
            }

            // 기타 단계(Label/Goto/Loop/Fault)는 기존 구현 사용 중이라면 동일하게 유지하면 됩니다.
            // (여기서 생략하지 않으면 파일이 너무 길어져서, 현재 프로젝트의 기존 구현을 그대로 두세요.)

            // 안전장치
            log.error(StructuredLog.event("scenario_unknown_step",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", index,
                    "stepClass", st.getClass().getName()));
            ctx.close();
            return;
        }
    }

    private void scheduleCloseAfterCompletion(ChannelHandlerContext ctx, EqpRuntime eqp) {
        if (closeScheduled) return;
        closeScheduled = true;

        ctx.channel().attr(ChannelAttributes.CLOSE_REASON)
                .set(ChannelAttributes.CLOSE_REASON_SCENARIO_COMPLETED);

        log.info(StructuredLog.event("scenario_close_scheduled",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "delayMs", CLOSE_GRACE_MS,
                "closeReason", ChannelAttributes.CLOSE_REASON_SCENARIO_COMPLETED));

        ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelWaitTimeout() {
        if (waitTimeoutFuture != null) {
            waitTimeoutFuture.cancel(false);
            waitTimeoutFuture = null;
        }
    }

    private void startForeverEmit(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        // 기존 구현 유지 (생략)
    }

    private void startFiniteEmitThenContinue(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        // 기존 구현 유지 (생략)
    }
}
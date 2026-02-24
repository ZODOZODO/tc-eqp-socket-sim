package com.nori.tc.eqpsim.socket.scenario.runtime;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ScenarioRunnerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerHandler.class);

    private final ScenarioPlan plan;

    private int index = 0;

    private WaitCmdStep waiting;
    private ScheduledFuture<?> waitTimeoutFuture;

    private final Map<Integer, Integer> loopRemaining = new HashMap<>();

    public ScenarioRunnerHandler(ScenarioPlan plan) {
        super(true);
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();

        log.info(StructuredLog.event("scenario_started",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "mode", eqp != null ? eqp.getMode() : "null",
                "endpointId", eqp != null ? eqp.getEndpointId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepCount", plan.getSteps().size()));

        advance(ctx);
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (waiting == null) return;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();

        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);
        if (cmdUpper == null) return;

        if (!waiting.getExpectedCmdUpper().equals(cmdUpper)) {
            // 예상 외 CMD 무시
            return;
        }

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
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile()));
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

            if (st instanceof LabelStep) {
                index++;
                continue;
            }

            if (st instanceof GotoStep g) {
                Integer next = plan.getLabelIndex().get(g.getLabel());

                log.info(StructuredLog.event("scenario_goto",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "label", g.getLabel(),
                        "targetIndex", next));

                index = (next != null) ? next : plan.getSteps().size();
                continue;
            }

            if (st instanceof LoopStep lp) {
                int stepIdx = index;
                int rem = loopRemaining.getOrDefault(stepIdx, lp.getCount());
                rem--;

                if (rem >= 0) {
                    loopRemaining.put(stepIdx, rem);
                    Integer next = plan.getLabelIndex().get(lp.getGotoLabel());

                    log.info(StructuredLog.event("scenario_loop_jump",
                            "eqpId", eqp.getEqpId(),
                            "connId", ctx.channel().id().asShortText(),
                            "scenarioFile", plan.getSourceFile(),
                            "stepIndex", index,
                            "gotoLabel", lp.getGotoLabel(),
                            "remaining", rem,
                            "targetIndex", next));

                    if (next == null) {
                        ctx.close();
                        return;
                    }
                    index = next;
                    continue;
                } else {
                    loopRemaining.remove(stepIdx);
                    index++;
                    continue;
                }
            }

            if (st instanceof FaultStep fs) {
                applyFault(ctx, eqp, fs);

                log.info(StructuredLog.event("scenario_fault",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", index,
                        "faultType", fs.getType(),
                        "scope", fs.getScopeMode()));

                index++;
                continue;
            }

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

    private void cancelWaitTimeout() {
        if (waitTimeoutFuture != null) {
            waitTimeoutFuture.cancel(false);
            waitTimeoutFuture = null;
        }
    }

    private void startForeverEmit(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        if (e.getMode() != EmitStep.Mode.INTERVAL) {
            return;
        }

        final long intervalMs = e.getIntervalOrWindowMs();
        final long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0;

        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (!ctx.channel().isActive()) return;

                String resolved = ScenarioTemplateResolver.resolve(e.getPayloadTemplate(), eqp);
                OutboundFrameSender.send(ctx, eqp, resolved);

                long delay = intervalMs;
                if (jitterMs > 0) {
                    delay += ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
                }
                ctx.executor().schedule(this, delay, TimeUnit.MILLISECONDS);
            }
        };

        ctx.executor().schedule(task, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void startFiniteEmitThenContinue(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        int count = ((EmitStep.CountFixed) e.getCount()).getValue();

        if (e.getMode() == EmitStep.Mode.INTERVAL) {
            scheduleIntervalFixedRate(ctx, eqp, e, count);
        } else {
            scheduleWindowRandom(ctx, eqp, e, count);
        }
    }

    private void scheduleIntervalFixedRate(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int count) {
        final long intervalMs = e.getIntervalOrWindowMs();
        final long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0;
        final long startAt = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            final int idx = i;
            long due = startAt + (long) idx * intervalMs;
            long delay = Math.max(0, due - System.currentTimeMillis());
            if (jitterMs > 0) {
                delay += ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
            }

            ctx.executor().schedule(() -> {
                if (!ctx.channel().isActive()) return;
                String resolved = ScenarioTemplateResolver.resolve(e.getPayloadTemplate(), eqp);
                OutboundFrameSender.send(ctx, eqp, resolved);
            }, delay, TimeUnit.MILLISECONDS);
        }

        long totalDelay = (long) (count - 1) * intervalMs + (jitterMs > 0 ? jitterMs : 0);
        ctx.executor().schedule(() -> {
            index++;
            advance(ctx);
        }, totalDelay, TimeUnit.MILLISECONDS);
    }

    private void scheduleWindowRandom(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int count) {
        final long windowMs = e.getIntervalOrWindowMs();

        Set<Long> offsets = new LinkedHashSet<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        while (offsets.size() < count) {
            offsets.add(rnd.nextLong(0, Math.max(1, windowMs)));
        }

        List<Long> sorted = new ArrayList<>(offsets);
        Collections.sort(sorted);

        for (long off : sorted) {
            ctx.executor().schedule(() -> {
                if (!ctx.channel().isActive()) return;
                String resolved = ScenarioTemplateResolver.resolve(e.getPayloadTemplate(), eqp);
                OutboundFrameSender.send(ctx, eqp, resolved);
            }, off, TimeUnit.MILLISECONDS);
        }

        ctx.executor().schedule(() -> {
            index++;
            advance(ctx);
        }, windowMs, TimeUnit.MILLISECONDS);
    }

    private void applyFault(ChannelHandlerContext ctx, EqpRuntime eqp, FaultStep fs) {
        FaultState state = ctx.channel().attr(ChannelAttributes.FAULT_STATE).get();
        if (state == null) {
            state = new FaultState();
            ctx.channel().attr(ChannelAttributes.FAULT_STATE).set(state);
        }

        switch (fs.getType()) {
            case DISCONNECT -> {
                long after = fs.getAfterMs() != null ? fs.getAfterMs() : 0;

                // ✅ 반드시 void 블록 람다로 고정 (Callable 오버로드와의 모호성 제거)
                ctx.executor().schedule(() -> { ctx.close(); }, after, TimeUnit.MILLISECONDS);
            }
            case CLEAR -> state.clearAll();
            default -> state.applyFault(fs);
        }
    }
}
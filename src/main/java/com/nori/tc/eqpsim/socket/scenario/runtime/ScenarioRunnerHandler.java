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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ScenarioRunnerHandler
 *
 * 역할:
 * - HandshakeHandler 완료 후 pipeline에 replace되어 시나리오 스텝을 실행한다.
 * - 지원 스텝: WAIT / SEND / EMIT(INTERVAL/WINDOW) / SLEEP / LABEL / GOTO / LOOP / FAULT
 *
 * ✅ [B2 수정] WINDOW emit 완료 콜백 중복 advance 방지
 *   - done >= totalCount → done == totalCount 로 변경
 *   - 여러 콜백이 동시에 조건을 만족해도 advance()는 정확히 1회만 호출
 *
 * ✅ [M1 수정] forever emit 채널 close 후 무한 스케줄 체인 방지
 *   - volatile emitStopped 플래그 도입
 *   - channelInactive에서 emitStopped = true 설정
 *   - 모든 emit 콜백 진입 시 emitStopped 확인 → true면 즉시 return
 *
 * ✅ [M2 수정] finite/window emit 채널 close 후 불필요한 실행 방지
 *   - 동일 emitStopped 플래그 사용
 *
 * ✅ [로깅 복원] 메시지 송수신 관측 로그
 *   - scenario_emit_send: log.debug → log.info (emit 송신 로그 복원)
 *   - eqp_rx: 설비가 수신한 모든 프레임 로그 (WAIT 매칭/불일치 구분)
 *   - eqp_tx: 설비가 송신한 모든 프레임 로그 (SEND/EMIT 통합)
 */
public class ScenarioRunnerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerHandler.class);

    /** ACTIVE 시나리오 완료 후 채널 close까지 대기 시간(ms) */
    private static final long CLOSE_GRACE_MS = 100;

    private final ScenarioPlan plan;
    private final ScenarioCompletionTracker tracker;

    // ─── 실행 상태 ──────────────────────────────────────────────────────────────

    /** handlerAdded/channelActive 중복 실행 방지 */
    private boolean started = false;

    /** ACTIVE 정상 종료 close 예약 중복 방지 */
    private boolean closeScheduled = false;

    /** 현재 실행 중인 스텝 인덱스 */
    private int stepIndex = 0;

    /**
     * ✅ [M1, M2 수정] emit 중지 플래그
     * - channelInactive 시 true로 설정
     * - 모든 emit 콜백이 이 값을 확인하여 채널 close 후 불필요한 실행/스케줄을 방지
     */
    private volatile boolean emitStopped = false;

    // ─── WAIT 상태 ──────────────────────────────────────────────────────────────

    /** 현재 대기 중인 WAIT 스텝. null이면 대기 중이 아님 */
    private WaitCmdStep waitingStep;

    /** WAIT 타임아웃 타이머 핸들 */
    private ScheduledFuture<?> waitTimeoutFuture;

    // ─── LOOP 상태 ──────────────────────────────────────────────────────────────

    /**
     * Loop 반복 횟수 추적: gotoLabel → 현재까지 반복한 횟수
     * 루프 완료 시 카운터 제거(재진입 허용)
     */
    private final Map<String, Integer> loopIterationCount = new HashMap<>();

    // ─── 생성자 ─────────────────────────────────────────────────────────────────

    public ScenarioRunnerHandler(ScenarioPlan plan) {
        this(plan, ScenarioCompletionTracker.NOOP);
    }

    public ScenarioRunnerHandler(ScenarioPlan plan, ScenarioCompletionTracker tracker) {
        super(true); // autoRelease=true
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    // ─── Netty 채널 이벤트 ──────────────────────────────────────────────────────

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

    /**
     * 수신 프레임 처리 (WAIT 스텝 매칭).
     *
     * ✅ [로깅 복원] 설비가 수신한 모든 프레임을 eqp_rx 이벤트로 INFO 로그
     * - WAIT 매칭 성공: matched=true
     * - WAIT 매칭 실패(불일치/무관 CMD): matched=false (계속 대기)
     * - WAIT 상태 아닐 때 수신된 프레임: unexpected=true
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);

        if (waitingStep == null) {
            // WAIT 상태가 아닌데 프레임이 수신됨(예상 외)
            log.info(StructuredLog.event("eqp_rx",
                    "eqpId", eqp != null ? eqp.getEqpId() : "null",
                    "connId", ctx.channel().id().asShortText(),
                    "cmd", cmdUpper,
                    "payload", frame,
                    "unexpected", true));
            return;
        }

        // ✅ [로깅 복원] 설비 수신 로그
        boolean matched = cmdUpper != null && waitingStep.getExpectedCmdUpper().equals(cmdUpper);
        log.info(StructuredLog.event("eqp_rx",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "cmd", cmdUpper,
                "payload", frame,
                "expected", waitingStep.getExpectedCmdUpper(),
                "matched", matched));

        if (!matched) return; // CMD 불일치 → 계속 대기

        // WAIT 완료 처리
        cancelWaitTimeout();
        WaitCmdStep done = waitingStep;
        waitingStep = null;

        log.info(StructuredLog.event("scenario_wait_matched",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "matchedCmd", done.getExpectedCmdUpper()));

        stepIndex++;
        advance(ctx);
    }

    /**
     * 채널 비활성화 처리.
     *
     * ✅ [M1, M2 수정] emitStopped = true 설정 → 모든 emit 콜백 중지
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            emitStopped = true; // emit 체인 중지 플래그
            cancelWaitTimeout();
        } finally {
            ctx.fireChannelInactive();
        }
    }

    // ─── 시나리오 시작 ────────────────────────────────────────────────────────

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

    // ─── 스텝 실행 루프 ──────────────────────────────────────────────────────

    private void advance(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            log.error(StructuredLog.event("scenario_eqp_attr_missing",
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile()));
            ctx.close();
            return;
        }

        while (true) {
            if (stepIndex >= plan.getSteps().size()) {
                handleScenarioCompleted(ctx, eqp);
                return;
            }

            ScenarioStep step = plan.getSteps().get(stepIndex);

            if (step instanceof WaitCmdStep w) {
                handleWaitStep(ctx, eqp, w);
                return;

            } else if (step instanceof SendStep s) {
                handleSendStep(ctx, eqp, s);
                stepIndex++;
                continue;

            } else if (step instanceof EmitStep e) {
                handleEmitStep(ctx, eqp, e);
                return;

            } else if (step instanceof SleepStep sl) {
                handleSleepStep(ctx, eqp, sl);
                return;

            } else if (step instanceof LabelStep) {
                stepIndex++;
                continue;

            } else if (step instanceof GotoStep g) {
                boolean ok = handleGotoStep(ctx, eqp, g);
                if (!ok) return;
                continue;

            } else if (step instanceof LoopStep lp) {
                boolean jumped = handleLoopStep(ctx, eqp, lp);
                if (jumped) {
                    continue;
                } else {
                    stepIndex++;
                    continue;
                }

            } else if (step instanceof FaultStep f) {
                handleFaultStep(ctx, eqp, f);
                if (f.getType() == FaultStep.Type.DISCONNECT) {
                    return;
                }
                stepIndex++;
                continue;

            } else {
                log.error(StructuredLog.event("scenario_unknown_step",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", stepIndex,
                        "stepClass", step.getClass().getName()));
                ctx.close();
                return;
            }
        }
    }

    // ─── 개별 스텝 핸들러 ────────────────────────────────────────────────────

    private void handleWaitStep(ChannelHandlerContext ctx, EqpRuntime eqp, WaitCmdStep w) {
        waitingStep = w;
        long timeoutSec = resolveWaitTimeout(w, eqp);

        log.info(StructuredLog.event("scenario_wait_started",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "expectedCmd", w.getExpectedCmdUpper(),
                "timeoutSec", timeoutSec));

        waitTimeoutFuture = ctx.executor().schedule(() -> {
            log.warn(StructuredLog.event("scenario_wait_timeout",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", stepIndex,
                    "expectedCmd", w.getExpectedCmdUpper(),
                    "timeoutSec", timeoutSec));
            ctx.close();
        }, timeoutSec, TimeUnit.SECONDS);
    }

    private long resolveWaitTimeout(WaitCmdStep w, EqpRuntime eqp) {
        if (w.getTimeoutOverrideSec() != null && w.getTimeoutOverrideSec() > 0) {
            return w.getTimeoutOverrideSec();
        }
        long eqpTimeout = eqp.getWaitTimeoutSec();
        return (eqpTimeout > 0) ? eqpTimeout : 60L;
    }

    /**
     * SEND: 즉시 1회 송신.
     *
     * ✅ [로깅 복원] eqp_tx 이벤트로 송신 내용 INFO 로그
     */
    private void handleSendStep(ChannelHandlerContext ctx, EqpRuntime eqp, SendStep s) {
        String resolved = ScenarioTemplateResolver.resolve(s.getPayloadTemplate(), eqp);

        // ✅ 설비 송신 로그
        log.info(StructuredLog.event("eqp_tx",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "type", "SEND",
                "stepIndex", stepIndex,
                "payload", resolved));

        OutboundFrameSender.send(ctx, eqp, resolved);

        log.info(StructuredLog.event("scenario_send",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "payload", resolved));
    }

    private void handleEmitStep(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        boolean isForever = e.getCount() instanceof EmitStep.CountForever;
        int totalCount = isForever ? 0 : ((EmitStep.CountFixed) e.getCount()).getValue();

        log.info(StructuredLog.event("scenario_emit_started",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "mode", e.getMode(),
                "intervalOrWindowMs", e.getIntervalOrWindowMs(),
                "count", isForever ? "forever" : totalCount,
                "jitterMs", e.getJitterMs() != null ? e.getJitterMs() : 0));

        if (isForever) {
            scheduleIntervalForever(ctx, eqp, e);
        } else if (e.getMode() == EmitStep.Mode.INTERVAL) {
            scheduleIntervalFinite(ctx, eqp, e, totalCount);
        } else {
            scheduleWindowEmit(ctx, eqp, e, totalCount);
        }
    }

    /**
     * EMIT INTERVAL (forever): 채널이 닫힐 때까지 무한 반복.
     *
     * ✅ [M1 수정] emitStopped 플래그로 채널 close 후 스케줄 체인 중지
     */
    private void scheduleIntervalForever(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        long intervalMs = e.getIntervalOrWindowMs();
        long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0L;

        Runnable[] selfRef = new Runnable[1];
        selfRef[0] = () -> {
            // [M1 수정] emitStopped 또는 채널 비활성 시 체인 중지
            if (emitStopped || !ctx.channel().isActive()) return;

            sendEmitPayload(ctx, eqp, e);

            // 다음 스케줄 (채널 active 상태에서만)
            long nextDelayMs = intervalMs + randomJitter(jitterMs);
            ctx.executor().schedule(selfRef[0], nextDelayMs, TimeUnit.MILLISECONDS);
        };

        long firstDelayMs = intervalMs + randomJitter(jitterMs);
        ctx.executor().schedule(selfRef[0], firstDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * EMIT INTERVAL (유한): N회 송신 후 다음 스텝으로.
     *
     * ✅ [M2 수정] emitStopped 플래그로 채널 close 후 실행 방지
     */
    private void scheduleIntervalFinite(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int totalCount) {
        long intervalMs = e.getIntervalOrWindowMs();
        long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0L;

        AtomicInteger remaining = new AtomicInteger(totalCount);

        Runnable[] selfRef = new Runnable[1];
        selfRef[0] = () -> {
            // [M2 수정] emitStopped 또는 채널 비활성 시 중지
            if (emitStopped || !ctx.channel().isActive()) return;

            sendEmitPayload(ctx, eqp, e);

            int left = remaining.decrementAndGet();
            if (left > 0) {
                long nextDelayMs = intervalMs + randomJitter(jitterMs);
                ctx.executor().schedule(selfRef[0], nextDelayMs, TimeUnit.MILLISECONDS);
            } else {
                log.info(StructuredLog.event("scenario_emit_completed",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", stepIndex,
                        "totalSent", totalCount,
                        "mode", "INTERVAL"));

                stepIndex++;
                advance(ctx);
            }
        };

        long firstDelayMs = intervalMs + randomJitter(jitterMs);
        ctx.executor().schedule(selfRef[0], firstDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * EMIT WINDOW (유한): windowMs 내 랜덤 N회.
     *
     * ✅ [B2 수정] done >= totalCount → done == totalCount
     *   - AtomicInteger.incrementAndGet()은 순서대로 실행되지만,
     *     done >= totalCount 조건이면 totalCount+1, +2... 도 advance()를 호출할 수 있음
     *   - done == totalCount 로 변경하여 정확히 1회만 advance() 호출을 보장
     *
     * ✅ [M2 수정] emitStopped 플래그로 채널 close 후 실행 방지
     */
    private void scheduleWindowEmit(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int totalCount) {
        long windowMs = e.getIntervalOrWindowMs();

        List<Long> delayList = new ArrayList<>(totalCount);
        for (int i = 0; i < totalCount; i++) {
            delayList.add(ThreadLocalRandom.current().nextLong(0, windowMs + 1));
        }
        Collections.sort(delayList);

        // 완료 카운터: 마지막 emit 1회만 advance()를 호출하도록 보장
        AtomicInteger doneCount = new AtomicInteger(0);

        for (long delayMs : delayList) {
            ctx.executor().schedule(() -> {
                // [M2 수정] emitStopped 또는 채널 비활성 시 중지
                if (emitStopped || !ctx.channel().isActive()) return;

                sendEmitPayload(ctx, eqp, e);

                // [B2 수정] done == totalCount: 정확히 마지막 1회만 advance() 호출
                int done = doneCount.incrementAndGet();
                if (done == totalCount) {
                    log.info(StructuredLog.event("scenario_emit_completed",
                            "eqpId", eqp.getEqpId(),
                            "connId", ctx.channel().id().asShortText(),
                            "scenarioFile", plan.getSourceFile(),
                            "stepIndex", stepIndex,
                            "totalSent", totalCount,
                            "mode", "WINDOW",
                            "windowMs", windowMs));

                    stepIndex++;
                    advance(ctx);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * EMIT payload 변수 치환 후 송신.
     *
     * ✅ [로깅 복원] scenario_emit_send: log.debug → log.info
     *   - 기존 DEBUG 레벨로 인해 기본 설정(INFO)에서 로그가 출력되지 않던 문제 수정
     *   - eqp_tx 이벤트로 설비 송신 내용을 명시적으로 기록
     */
    private void sendEmitPayload(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        String resolved = ScenarioTemplateResolver.resolve(e.getPayloadTemplate(), eqp);

        // ✅ [로깅 복원] 설비 송신 로그 (INFO 레벨)
        log.info(StructuredLog.event("eqp_tx",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "type", "EMIT",
                "stepIndex", stepIndex,
                "payload", resolved));

        OutboundFrameSender.send(ctx, eqp, resolved);

        // 기존 scenario_emit_send 이벤트 유지 (DEBUG → INFO 변경)
        log.info(StructuredLog.event("scenario_emit_send",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "stepIndex", stepIndex,
                "payload", resolved));
    }

    private void handleSleepStep(ChannelHandlerContext ctx, EqpRuntime eqp, SleepStep sl) {
        long sleepMs = sl.getSleepMs();

        log.info(StructuredLog.event("scenario_sleep",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "sleepMs", sleepMs));

        ctx.executor().schedule(() -> {
            if (emitStopped || !ctx.channel().isActive()) return;
            stepIndex++;
            advance(ctx);
        }, sleepMs, TimeUnit.MILLISECONDS);
    }

    private boolean handleGotoStep(ChannelHandlerContext ctx, EqpRuntime eqp, GotoStep g) {
        Integer targetIndex = plan.getLabelIndex().get(g.getLabel());
        if (targetIndex == null) {
            log.error(StructuredLog.event("scenario_goto_label_missing",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", stepIndex,
                    "label", g.getLabel()));
            ctx.close();
            return false;
        }

        log.info(StructuredLog.event("scenario_goto",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "fromIndex", stepIndex,
                "label", g.getLabel(),
                "toIndex", targetIndex));

        stepIndex = targetIndex;
        return true;
    }

    private boolean handleLoopStep(ChannelHandlerContext ctx, EqpRuntime eqp, LoopStep lp) {
        String label = lp.getGotoLabel();
        int maxCount = lp.getCount();
        int currentIteration = loopIterationCount.getOrDefault(label, 0);

        if (currentIteration < maxCount) {
            loopIterationCount.put(label, currentIteration + 1);

            Integer targetIndex = plan.getLabelIndex().get(label);
            if (targetIndex == null) {
                log.error(StructuredLog.event("scenario_loop_label_missing",
                        "eqpId", eqp.getEqpId(),
                        "connId", ctx.channel().id().asShortText(),
                        "scenarioFile", plan.getSourceFile(),
                        "stepIndex", stepIndex,
                        "label", label));
                ctx.close();
                return false;
            }

            log.info(StructuredLog.event("scenario_loop",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "fromIndex", stepIndex,
                    "label", label,
                    "toIndex", targetIndex,
                    "iteration", currentIteration + 1,
                    "maxCount", maxCount));

            stepIndex = targetIndex;
            return true;

        } else {
            loopIterationCount.remove(label);

            log.info(StructuredLog.event("scenario_loop_completed",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", stepIndex,
                    "label", label,
                    "completedCount", maxCount));

            return false;
        }
    }

    private void handleFaultStep(ChannelHandlerContext ctx, EqpRuntime eqp, FaultStep f) {
        log.info(StructuredLog.event("scenario_fault_apply",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "faultType", f.getType(),
                "scopeMode", f.getScopeMode()));

        if (f.getType() == FaultStep.Type.DISCONNECT) {
            scheduleDisconnect(ctx, eqp, f);
            return;
        }

        FaultState fs = ctx.channel().attr(ChannelAttributes.FAULT_STATE).get();
        if (fs == null) {
            log.warn(StructuredLog.event("scenario_fault_state_missing",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "stepIndex", stepIndex));
            return;
        }

        fs.applyFault(f);
    }

    private void scheduleDisconnect(ChannelHandlerContext ctx, EqpRuntime eqp, FaultStep f) {
        long afterMs = f.getAfterMs() != null ? f.getAfterMs() : 0L;
        long downMs = f.getDownMs() != null ? f.getDownMs() : 0L;

        log.info(StructuredLog.event("scenario_disconnect_scheduled",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "afterMs", afterMs,
                "downMs", downMs));

        ctx.executor().schedule(() -> {
            if (!ctx.channel().isActive()) return;

            log.info(StructuredLog.event("scenario_disconnect_executing",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "downMs", downMs));

            ctx.close();
        }, afterMs, TimeUnit.MILLISECONDS);
    }

    // ─── 시나리오 완료 ─────────────────────────────────────────────────────────

    private void handleScenarioCompleted(ChannelHandlerContext ctx, EqpRuntime eqp) {
        log.info(StructuredLog.event("scenario_completed",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile()));

        tracker.markScenarioCompleted(eqp.getEqpId());

        if (eqp.getMode() == EqpProperties.Mode.ACTIVE) {
            scheduleCloseAfterCompletion(ctx, eqp);
        } else {
            log.info(StructuredLog.event("scenario_passive_keepalive",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile()));
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

    // ─── 유틸리티 ──────────────────────────────────────────────────────────────

    private void cancelWaitTimeout() {
        if (waitTimeoutFuture != null) {
            waitTimeoutFuture.cancel(false);
            waitTimeoutFuture = null;
        }
    }

    private static long randomJitter(long jitterMs) {
        if (jitterMs <= 0) return 0L;
        return ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
    }
}
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
 * - HandshakeHandler 완료 후 pipeline에 replace되어 시나리오 스텝을 실행합니다.
 * - 지원 스텝: WAIT / SEND / EMIT(INTERVAL/WINDOW) / SLEEP / LABEL / GOTO / LOOP / FAULT
 *
 * 실행 모델:
 * - advance()가 동기 스텝은 루프로 처리하고, 비동기 스텝(WAIT/EMIT/SLEEP)은 return합니다.
 * - 비동기 완료 콜백에서 stepIndex를 증가시킨 뒤 advance()를 재호출합니다.
 *
 * 스레드 안전:
 * - 모든 실행은 Netty EventLoop에서 수행되므로 별도 동기화가 불필요합니다.
 */
public class ScenarioRunnerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunnerHandler.class);

    /** ACTIVE 시나리오 완료 후 채널 close까지 대기 시간(ms) */
    private static final long CLOSE_GRACE_MS = 100;

    private final ScenarioPlan plan;
    private final ScenarioCompletionTracker tracker;

    // ─── 실행 상태 ──────────────────────────────────────────────────

    /** handlerAdded/channelActive 중복 실행 방지 플래그 */
    private boolean started = false;

    /** ACTIVE 정상 종료 close 예약 중복 방지 플래그 */
    private boolean closeScheduled = false;

    /** 현재 실행 중인 스텝의 인덱스 (plan.getSteps() 기준) */
    private int stepIndex = 0;

    // ─── WAIT 상태 ──────────────────────────────────────────────────

    /** 현재 대기 중인 WAIT 스텝. null이면 대기 중이 아님 */
    private WaitCmdStep waitingStep;

    /** WAIT 타임아웃 타이머 핸들 */
    private ScheduledFuture<?> waitTimeoutFuture;

    // ─── LOOP 상태 ──────────────────────────────────────────────────

    /**
     * Loop 반복 횟수 추적: gotoLabel → 현재까지 반복한 횟수
     * - LoopStep에 도달할 때마다 카운터를 증가시키고, count에 도달하면 카운터를 제거합니다.
     * - 중첩 루프 및 재진입이 가능합니다.
     */
    private final Map<String, Integer> loopIterationCount = new HashMap<>();

    // ─── 생성자 ─────────────────────────────────────────────────────

    public ScenarioRunnerHandler(ScenarioPlan plan) {
        this(plan, ScenarioCompletionTracker.NOOP);
    }

    public ScenarioRunnerHandler(ScenarioPlan plan, ScenarioCompletionTracker tracker) {
        super(true); // autoRelease=true: channelRead0에서 ByteBuf 자동 release
        this.plan = Objects.requireNonNull(plan, "plan must not be null");
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    // ─── Netty 채널 이벤트 ──────────────────────────────────────────

    /**
     * pipeline.replace()로 이 핸들러가 추가될 때 호출됩니다.
     * 채널이 이미 active 상태라면 즉시 시나리오를 시작합니다.
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            // EventLoop 안에서 실행되고 있으므로 즉시 실행 가능하지만,
            // 핸들러 등록이 완전히 마무리된 후 시작하기 위해 execute()로 감쌉니다.
            ctx.executor().execute(() -> startIfNeeded(ctx, "handlerAdded"));
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        startIfNeeded(ctx, "channelActive");
        ctx.fireChannelActive();
    }

    /**
     * 수신 프레임 처리 (WAIT 스텝 매칭에 사용)
     * - 대기 중인 CMD와 일치하면 WAIT 완료 처리 후 다음 스텝으로 진행합니다.
     * - 불일치 CMD는 무시합니다(시나리오 규칙).
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (waitingStep == null) return;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);

        log.info(StructuredLog.event("scenario_rx_wait",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "expectedCmd", waitingStep.getExpectedCmdUpper(),
                "cmd", cmdUpper,
                "payload", frame));

        // CMD가 없거나 예상 CMD와 불일치 → 무시하고 계속 대기
        if (cmdUpper == null) return;
        if (!waitingStep.getExpectedCmdUpper().equals(cmdUpper)) return;

        // WAIT 완료 처리
        cancelWaitTimeout();
        WaitCmdStep matched = waitingStep;
        waitingStep = null;

        log.info(StructuredLog.event("scenario_wait_matched",
                "eqpId", eqp != null ? eqp.getEqpId() : "null",
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "matchedCmd", matched.getExpectedCmdUpper()));

        stepIndex++;
        advance(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            // 채널이 끊어지면 WAIT 타임아웃 타이머를 취소합니다.
            cancelWaitTimeout();
        } finally {
            ctx.fireChannelInactive();
        }
    }

    // ─── 시나리오 시작 ───────────────────────────────────────────────

    /** 중복 시작 방지 후 시나리오 실행을 시작합니다. */
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

    // ─── 스텝 실행 루프 ──────────────────────────────────────────────

    /**
     * 현재 stepIndex부터 순차적으로 스텝을 실행합니다.
     *
     * 동기 스텝(SEND/LABEL/GOTO/LOOP): stepIndex 증가 후 루프 계속
     * 비동기 스텝(WAIT/EMIT/SLEEP/FAULT-DISCONNECT): return 후 콜백에서 재호출
     */
    private void advance(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            log.error(StructuredLog.event("scenario_eqp_attr_missing",
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile()));
            ctx.close();
            return;
        }

        // 스텝 루프: 동기 스텝은 계속 처리, 비동기 스텝은 return
        while (true) {

            // ─ 시나리오 완료 ─────────────────────────────────────────
            if (stepIndex >= plan.getSteps().size()) {
                handleScenarioCompleted(ctx, eqp);
                return;
            }

            ScenarioStep step = plan.getSteps().get(stepIndex);

            // ─ WAIT ──────────────────────────────────────────────────
            if (step instanceof WaitCmdStep w) {
                handleWaitStep(ctx, eqp, w);
                return; // 비동기: channelRead0에서 완료 후 재호출

            // ─ SEND ──────────────────────────────────────────────────
            } else if (step instanceof SendStep s) {
                handleSendStep(ctx, eqp, s);
                stepIndex++;
                continue; // 동기: 즉시 다음 스텝

            // ─ EMIT ──────────────────────────────────────────────────
            } else if (step instanceof EmitStep e) {
                handleEmitStep(ctx, eqp, e);
                return; // 비동기: 완료 콜백에서 advance() 재호출 (forever는 재호출 없음)

            // ─ SLEEP ─────────────────────────────────────────────────
            } else if (step instanceof SleepStep sl) {
                handleSleepStep(ctx, eqp, sl);
                return; // 비동기: 타이머 완료 후 advance() 재호출

            // ─ LABEL ─────────────────────────────────────────────────
            // label은 인덱스 정보만 제공하는 마커이므로 실행 시에는 skip합니다.
            } else if (step instanceof LabelStep) {
                stepIndex++;
                continue;

            // ─ GOTO ──────────────────────────────────────────────────
            } else if (step instanceof GotoStep g) {
                boolean ok = handleGotoStep(ctx, eqp, g);
                if (!ok) return; // 라벨 미발견 시 채널 close
                continue; // stepIndex가 변경되었으므로 루프 재시작

            // ─ LOOP ──────────────────────────────────────────────────
            } else if (step instanceof LoopStep lp) {
                boolean jumped = handleLoopStep(ctx, eqp, lp);
                if (jumped) {
                    continue; // 루프 점프: 변경된 stepIndex로 재시작
                } else {
                    stepIndex++;
                    continue; // 루프 종료: 다음 스텝으로
                }

            // ─ FAULT ─────────────────────────────────────────────────
            } else if (step instanceof FaultStep f) {
                handleFaultStep(ctx, eqp, f);
                // DISCONNECT는 채널 close 예약 후 시나리오 진행 불가
                if (f.getType() == FaultStep.Type.DISCONNECT) {
                    return;
                }
                stepIndex++;
                continue;

            // ─ 알 수 없는 스텝 타입 ──────────────────────────────────
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

    // ─── 개별 스텝 핸들러 ────────────────────────────────────────────

    /**
     * WAIT: TC가 특정 CMD를 보낼 때까지 대기합니다.
     * - 타임아웃 초과 시 채널을 close합니다.
     */
    private void handleWaitStep(ChannelHandlerContext ctx, EqpRuntime eqp, WaitCmdStep w) {
        waitingStep = w;

        long timeoutSec = resolveWaitTimeout(w, eqp);
        final long finalTimeoutSec = timeoutSec;

        log.info(StructuredLog.event("scenario_wait_started",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "expectedCmd", w.getExpectedCmdUpper(),
                "timeoutSec", finalTimeoutSec));

        waitTimeoutFuture = ctx.executor().schedule(() -> {
            log.warn(StructuredLog.event("scenario_wait_timeout",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", stepIndex,
                    "expectedCmd", w.getExpectedCmdUpper(),
                    "timeoutSec", finalTimeoutSec));
            ctx.close();
        }, finalTimeoutSec, TimeUnit.SECONDS);
    }

    /** WAIT 타임아웃: 스텝 오버라이드 → EQP 기본값 → 60초 순으로 결정 */
    private long resolveWaitTimeout(WaitCmdStep w, EqpRuntime eqp) {
        if (w.getTimeoutOverrideSec() != null && w.getTimeoutOverrideSec() > 0) {
            return w.getTimeoutOverrideSec();
        }
        long eqpTimeout = eqp.getWaitTimeoutSec();
        return (eqpTimeout > 0) ? eqpTimeout : 60L;
    }

    /**
     * SEND: payload를 즉시 한 번 송신합니다.
     * - 변수 치환({eqpid}, {var.xxx})을 적용합니다.
     */
    private void handleSendStep(ChannelHandlerContext ctx, EqpRuntime eqp, SendStep s) {
        String resolved = ScenarioTemplateResolver.resolve(s.getPayloadTemplate(), eqp);
        OutboundFrameSender.send(ctx, eqp, resolved);

        log.info(StructuredLog.event("scenario_send",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "payload", resolved));
    }

    /**
     * EMIT: 주기 또는 윈도우 기반으로 반복 송신합니다.
     * - INTERVAL + forever: 무한 반복 (시나리오 진행 없음)
     * - INTERVAL + count:   N회 후 다음 스텝으로
     * - WINDOW + count:     windowMs 안에 랜덤 N회 후 다음 스텝으로
     */
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
            // forever는 시나리오 완료가 없으므로 advance()를 재호출하지 않습니다.
            scheduleIntervalForever(ctx, eqp, e);
        } else if (e.getMode() == EmitStep.Mode.INTERVAL) {
            scheduleIntervalFinite(ctx, eqp, e, totalCount);
        } else {
            scheduleWindowEmit(ctx, eqp, e, totalCount);
        }
    }

    /**
     * EMIT INTERVAL (forever): 채널이 닫힐 때까지 주기마다 무한 송신합니다.
     * 재귀적 schedule로 구현합니다.
     */
    private void scheduleIntervalForever(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        long intervalMs = e.getIntervalOrWindowMs();
        long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0L;

        // 람다가 자기 자신을 참조하기 위해 배열을 사용합니다.
        Runnable[] selfRef = new Runnable[1];
        selfRef[0] = () -> {
            if (!ctx.channel().isActive()) return;

            sendEmitPayload(ctx, eqp, e);

            long nextDelayMs = intervalMs + randomJitter(jitterMs);
            ctx.executor().schedule(selfRef[0], nextDelayMs, TimeUnit.MILLISECONDS);
        };

        long firstDelayMs = intervalMs + randomJitter(jitterMs);
        ctx.executor().schedule(selfRef[0], firstDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * EMIT INTERVAL (유한): N회 송신 후 다음 스텝으로 진행합니다.
     * AtomicInteger로 남은 횟수를 추적합니다.
     */
    private void scheduleIntervalFinite(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int totalCount) {
        long intervalMs = e.getIntervalOrWindowMs();
        long jitterMs = e.getJitterMs() != null ? e.getJitterMs() : 0L;

        AtomicInteger remaining = new AtomicInteger(totalCount);

        Runnable[] selfRef = new Runnable[1];
        selfRef[0] = () -> {
            if (!ctx.channel().isActive()) return;

            sendEmitPayload(ctx, eqp, e);

            int left = remaining.decrementAndGet();
            if (left > 0) {
                long nextDelayMs = intervalMs + randomJitter(jitterMs);
                ctx.executor().schedule(selfRef[0], nextDelayMs, TimeUnit.MILLISECONDS);
            } else {
                // 모든 emit 완료 → 다음 스텝 진행
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
     * EMIT WINDOW (유한): windowMs 내 랜덤 시각 N개를 생성하여 스케줄링합니다.
     * 마지막 emit 완료 후 다음 스텝으로 진행합니다.
     */
    private void scheduleWindowEmit(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e, int totalCount) {
        long windowMs = e.getIntervalOrWindowMs();

        // 0 ~ windowMs 범위의 랜덤 지연 시각 생성 (오름차순 정렬)
        List<Long> delayList = new ArrayList<>(totalCount);
        for (int i = 0; i < totalCount; i++) {
            delayList.add(ThreadLocalRandom.current().nextLong(0, windowMs + 1));
        }
        Collections.sort(delayList);

        AtomicInteger doneCount = new AtomicInteger(0);

        for (long delayMs : delayList) {
            ctx.executor().schedule(() -> {
                if (!ctx.channel().isActive()) return;

                sendEmitPayload(ctx, eqp, e);

                int done = doneCount.incrementAndGet();
                if (done >= totalCount) {
                    // 마지막 emit 완료 → 다음 스텝 진행
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

    /** EMIT payload를 변수 치환 후 송신합니다. */
    private void sendEmitPayload(ChannelHandlerContext ctx, EqpRuntime eqp, EmitStep e) {
        String resolved = ScenarioTemplateResolver.resolve(e.getPayloadTemplate(), eqp);
        OutboundFrameSender.send(ctx, eqp, resolved);

        log.debug(StructuredLog.event("scenario_emit_send",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "stepIndex", stepIndex,
                "payload", resolved));
    }

    /**
     * SLEEP: 지정된 시간 대기 후 다음 스텝으로 진행합니다.
     */
    private void handleSleepStep(ChannelHandlerContext ctx, EqpRuntime eqp, SleepStep sl) {
        long sleepMs = sl.getSleepMs();

        log.info(StructuredLog.event("scenario_sleep",
                "eqpId", eqp.getEqpId(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile(),
                "stepIndex", stepIndex,
                "sleepMs", sleepMs));

        ctx.executor().schedule(() -> {
            stepIndex++;
            advance(ctx);
        }, sleepMs, TimeUnit.MILLISECONDS);
    }

    /**
     * GOTO: 지정된 label의 stepIndex로 점프합니다.
     * @return 점프 성공 시 true, 라벨 미발견 시 false(채널 close)
     */
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

    /**
     * LOOP: gotoLabel 위치로 count회 점프 후 종료합니다.
     * - 반복 횟수는 loopIterationCount로 추적합니다.
     * - 루프 완료 후 카운터를 제거하여 재진입(루프 재실행)을 허용합니다.
     *
     * @return 점프가 발생했으면 true, 루프 종료이면 false
     */
    private boolean handleLoopStep(ChannelHandlerContext ctx, EqpRuntime eqp, LoopStep lp) {
        String label = lp.getGotoLabel();
        int maxCount = lp.getCount();
        int currentIteration = loopIterationCount.getOrDefault(label, 0);

        if (currentIteration < maxCount) {
            // 반복 횟수 증가 후 점프
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
            return true; // 점프 발생

        } else {
            // 루프 완료: 카운터 제거(재진입 허용)
            loopIterationCount.remove(label);

            log.info(StructuredLog.event("scenario_loop_completed",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile(),
                    "stepIndex", stepIndex,
                    "label", label,
                    "completedCount", maxCount));

            return false; // 루프 종료
        }
    }

    /**
     * FAULT: 채널의 FaultState에 장애 상태를 적용합니다.
     * - CLEAR: 모든 장애 해제
     * - DISCONNECT: afterMs 후 채널을 close합니다(별도 처리)
     * - 나머지: FaultState에 적용 후 OutboundFrameSender에서 자동 사용
     */
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

    /**
     * DISCONNECT: afterMs 후 채널을 close합니다.
     * - ACTIVE 재연결 여부는 ActiveClientConnector의 backoff 정책에 위임합니다.
     * - downMs는 현재 "재연결 금지 시간"으로 설계되었으나, 구현상 로그만 출력합니다.
     */
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

    // ─── 시나리오 완료 ───────────────────────────────────────────────

    /**
     * 모든 스텝이 완료되었을 때 호출됩니다.
     * - ACTIVE: close 예약 (재연결 금지 마킹 포함)
     * - PASSIVE: 연결 유지 (TC가 close할 때까지 대기)
     */
    private void handleScenarioCompleted(ChannelHandlerContext ctx, EqpRuntime eqp) {
        log.info(StructuredLog.event("scenario_completed",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "connId", ctx.channel().id().asShortText(),
                "scenarioFile", plan.getSourceFile()));

        // 전역 완료 카운터 증가 (ScenarioCompletionCoordinator에서 프로세스 종료 판단)
        tracker.markScenarioCompleted(eqp.getEqpId());

        if (eqp.getMode() == EqpProperties.Mode.ACTIVE) {
            scheduleCloseAfterCompletion(ctx, eqp);
        } else {
            // PASSIVE: 시나리오 완료 후에도 연결 유지 (TC가 끊을 때까지)
            log.info(StructuredLog.event("scenario_passive_keepalive",
                    "eqpId", eqp.getEqpId(),
                    "connId", ctx.channel().id().asShortText(),
                    "scenarioFile", plan.getSourceFile()));
        }
    }

    /**
     * ACTIVE 정상 완료 시 CLOSE_REASON을 마킹하고 close를 예약합니다.
     * - CLOSE_REASON_SCENARIO_COMPLETED 마킹으로 ActiveClientConnector가 재연결을 금지합니다.
     */
    private void scheduleCloseAfterCompletion(ChannelHandlerContext ctx, EqpRuntime eqp) {
        if (closeScheduled) return;
        closeScheduled = true;

        // 재연결 금지 마킹: ActiveClientConnector의 closeFuture 리스너에서 참조합니다.
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

    // ─── 유틸리티 ────────────────────────────────────────────────────

    /** WAIT 타임아웃 타이머를 취소합니다. */
    private void cancelWaitTimeout() {
        if (waitTimeoutFuture != null) {
            waitTimeoutFuture.cancel(false);
            waitTimeoutFuture = null;
        }
    }

    /**
     * jitter 범위 내 랜덤 지연을 반환합니다.
     * @param jitterMs 최대 지터(ms). 0이면 0을 반환합니다.
     */
    private static long randomJitter(long jitterMs) {
        if (jitterMs <= 0) return 0L;
        return ThreadLocalRandom.current().nextLong(0, jitterMs + 1);
    }
}
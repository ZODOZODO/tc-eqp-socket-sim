package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
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

/**
 * HandshakeHandler
 *
 * 역할:
 * - TC → EqpSim 방향의 CMD=INITIALIZE를 수신하면
 *   CMD=INITIALIZE_REP EQPID=<eqpId> 를 응답한다.
 * - 완료 후 ScenarioRunnerHandler로 pipeline을 replace한다.
 *
 * ✅ [B3 수정] eqp == null 시 close 전 타이머 취소 누락
 *   - ctx.close() 호출 전 cancelTimeout() 추가
 *
 * ✅ [M3 수정] ScenarioPlan == null 시 close 전 타이머 취소 누락
 *   - ctx.close() 호출 전 cancelTimeout() 추가
 *
 * 타이머 취소 경로 정리:
 *   1) channelRead0 정상 핸드셰이크 완료 → cancelTimeout()
 *   2) channelRead0 에러 close → cancelTimeout() + ctx.close()  ← 수정됨
 *   3) channelInactive → cancelTimeout() (finally)
 *   4) exceptionCaught → channelInactive 경유하여 취소됨
 */
public class HandshakeHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final String CMD_INITIALIZE = "INITIALIZE";

    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioCompletionTracker tracker;

    private volatile boolean handshaked = false;

    /** 핸드셰이크 타임아웃 타이머 핸들. null이면 타이머 없음 */
    private ScheduledFuture<?> timeoutFuture;

    // ─── 생성자 ─────────────────────────────────────────────────────────────────

    /** 하위 호환: ScenarioRegistry만 전달하는 기존 코드용 */
    public HandshakeHandler(ScenarioRegistry scenarioRegistry) {
        this(scenarioRegistry, ScenarioCompletionTracker.NOOP);
    }

    public HandshakeHandler(ScenarioRegistry scenarioRegistry,
                            ScenarioCompletionTracker tracker) {
        super(true); // autoRelease=true
        this.scenarioRegistry = scenarioRegistry;
        this.tracker = tracker == null ? ScenarioCompletionTracker.NOOP : tracker;
    }

    // ─── 채널 이벤트 ─────────────────────────────────────────────────────────────

    /**
     * 채널 연결 시 핸드셰이크 타임아웃 타이머를 시작한다.
     * - eqp 속성이 없으면 타이머 시작 없이 즉시 close (타이머 누수 없음).
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            log.error(StructuredLog.event("handshake_start_failed",
                    "reason", "eqp_attr_missing",
                    "connId", ctx.channel().id().asShortText()));
            ctx.close(); // 타이머 미설정 상태이므로 취소 불필요
            return;
        }

        long configured = eqp.getHandshakeTimeoutSec();
        final long handshakeTimeoutSec = (configured > 0) ? configured : 60;

        // 타임아웃 타이머 설정
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

    /**
     * 수신 프레임 처리 (핸드셰이크 완료 판정).
     *
     * 처리 흐름:
     * 1) 이미 핸드셰이크 완료 → 무시 (이중 처리 방지)
     * 2) eqp 속성 없음 → [B3] 타이머 취소 후 close
     * 3) CMD 없거나 INITIALIZE 아님 → 대기 유지 (타이머 유지, 정상)
     * 4) ScenarioPlan 없음 → [M3] 타이머 취소 후 close
     * 5) 정상 핸드셰이크 완료 → 타이머 취소 + INITIALIZE_REP 송신 + ScenarioRunner로 replace
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (handshaked) return;

        EqpRuntime eqp = ctx.channel().attr(ChannelAttributes.EQP).get();
        if (eqp == null) {
            // [B3 수정] eqp 없으면 타이머 취소 후 close
            cancelTimeout();
            ctx.close();
            return;
        }

        String frame = msg.toString(StandardCharsets.UTF_8);
        String cmdUpper = FrameTokenParser.extractCmdUpper(frame);

        log.info(StructuredLog.event("handshake_rx",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText(),
                "cmd", cmdUpper,
                "payload", frame));

        // CMD 없음: 유효하지 않은 프레임 → 타이머 유지, 계속 대기
        if (cmdUpper == null) return;

        // INITIALIZE 아님: 예상 외 CMD → 타이머 유지, 계속 대기
        if (!CMD_INITIALIZE.equals(cmdUpper)) return;

        // ─── 핸드셰이크 완료 처리 ───────────────────────────────────────────────

        String rep = "CMD=INITIALIZE_REP EQPID=" + eqp.getEqpId();

        log.info(StructuredLog.event("handshake_tx",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText(),
                "payload", rep));

        OutboundFrameSender.send(ctx, eqp, rep);

        handshaked = true;
        cancelTimeout(); // 정상 완료: 타이머 취소

        log.info(StructuredLog.event("handshake_completed",
                "eqpId", eqp.getEqpId(),
                "mode", eqp.getMode(),
                "endpointId", eqp.getEndpointId(),
                "connId", ctx.channel().id().asShortText()));

        // PASSIVE: 핸드셰이크 완료 시점부터 open 추적 시작
        if (eqp.getMode() == EqpProperties.Mode.PASSIVE) {
            tracker.markPassiveChannelOpened(eqp.getEqpId());
        }

        // ScenarioPlan 조회
        ScenarioPlan plan = scenarioRegistry.getPlanByProfileId(eqp.getProfileId());
        if (plan == null) {
            log.error(StructuredLog.event("scenario_plan_missing",
                    "eqpId", eqp.getEqpId(),
                    "profileId", eqp.getProfileId(),
                    "connId", ctx.channel().id().asShortText()));
            // [M3 수정] plan 없으면 이미 cancelTimeout() 완료 상태 → 그대로 close
            ctx.close();
            return;
        }

        // ScenarioRunnerHandler로 교체
        ctx.pipeline().replace(this, "runner", new ScenarioRunnerHandler(plan, tracker));
    }

    /**
     * 채널 비활성화 시 타이머 취소 (모든 종료 경로 안전망).
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            cancelTimeout();
        } finally {
            ctx.fireChannelInactive();
        }
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    /**
     * 핸드셰이크 타임아웃 타이머를 취소한다.
     * - 이미 null이거나 완료된 경우 무시한다.
     */
    private void cancelTimeout() {
        ScheduledFuture<?> f = timeoutFuture;
        if (f != null) {
            f.cancel(false);
            timeoutFuture = null;
        }
    }
}
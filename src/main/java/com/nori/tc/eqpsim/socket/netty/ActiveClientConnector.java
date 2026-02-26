package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EndpointsProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.HostPort;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * ActiveClientConnector
 *
 * 역할:
 * - ACTIVE 모드 EQP 1개의 연결/재연결/종료를 담당합니다.
 * - NettyTransportLifecycle에서 EQP별로 1개씩 생성합니다.
 *
 * 연결 정책:
 * - 시나리오 완료(CLOSE_REASON_SCENARIO_COMPLETED)로 close된 경우 재연결하지 않습니다.
 * - 비정상 close(연결 실패, 네트워크 오류 등)는 지수 backoff로 재연결을 시도합니다.
 *
 * 스레드 안전:
 * - stopped, activeChannel은 volatile로 선언되어 EventLoop/외부 스레드 모두에서 안전하게 읽을 수 있습니다.
 * - reconnectAttempt는 Netty EventLoop 스레드에서만 접근하므로 volatile 불필요합니다.
 */
public final class ActiveClientConnector {

    private static final Logger log = LoggerFactory.getLogger(ActiveClientConnector.class);

    private final EqpRuntime eqp;
    private final EventLoopGroup group;
    private final EndpointsProperties.ActiveBackoffProperties backoffProps;
    private final Bootstrap bootstrap;

    /** 비정상 close 이후 재연결 시도 횟수. 성공 시 0으로 초기화됩니다. */
    private long reconnectAttempt = 0;

    /**
     * 재연결 금지 플래그.
     * - stop() 호출 시 true
     * - SCENARIO_COMPLETED close 시 true
     */
    private volatile boolean stopped = false;

    /** 현재 활성 채널. 연결 전이거나 close 직후에는 null일 수 있습니다. */
    private volatile Channel activeChannel;

    // ─── 생성자 ─────────────────────────────────────────────────────

    /**
     * @param eqp             연결할 EQP의 런타임 정보
     * @param group           Netty EventLoopGroup (NettyTransportLifecycle의 workerGroup 공유)
     * @param backoffProps    재연결 backoff 설정
     * @param scenarioRegistry 시나리오 plan 조회용 레지스트리
     * @param tracker         시나리오 완료/채널 open-close 추적기
     */
    public ActiveClientConnector(EqpRuntime eqp,
                                 EventLoopGroup group,
                                 EndpointsProperties.ActiveBackoffProperties backoffProps,
                                 ScenarioRegistry scenarioRegistry,
                                 ScenarioCompletionTracker tracker) {
        this.eqp = eqp;
        this.group = group;
        this.backoffProps = backoffProps;

        // Bootstrap은 재사용 가능하므로 생성자에서 1회 구성합니다.
        this.bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ActiveChannelInitializer(eqp, scenarioRegistry, tracker));
    }

    // ─── 공개 API ────────────────────────────────────────────────────

    /** EQP ID 반환 (외부 식별용) */
    public String getEqpId() {
        return eqp.getEqpId();
    }

    /**
     * 즉시 연결을 시도합니다.
     * - stopped=true 이면 즉시 반환합니다.
     * - 연결 성공 시 채널 close 리스너를 등록하여 재연결/종료를 판단합니다.
     * - 연결 실패 시 backoff 스케줄로 재시도합니다.
     */
    public void connectNow() {
        if (stopped) return;

        HostPort target = eqp.getEndpointAddress();

        log.info(StructuredLog.event("active_connecting",
                "eqpId", eqp.getEqpId(),
                "endpointId", eqp.getEndpointId(),
                "target", target.host() + ":" + target.port(),
                "reconnectAttempt", reconnectAttempt));

        ChannelFuture connectFuture = bootstrap.connect(target.host(), target.port());
        connectFuture.addListener((ChannelFutureListener) future -> {
            // stop()이 connect 도중에 호출된 경우
            if (stopped) {
                if (future.channel() != null) {
                    future.channel().close();
                }
                return;
            }

            if (!future.isSuccess()) {
                scheduleReconnect("connect_failed");
                return;
            }

            // 연결 성공
            activeChannel = future.channel();
            reconnectAttempt = 0;

            log.info(StructuredLog.event("active_connected",
                    "eqpId", eqp.getEqpId(),
                    "connId", activeChannel.id().asShortText(),
                    "remote", String.valueOf(activeChannel.remoteAddress()),
                    "local", String.valueOf(activeChannel.localAddress())));

            // 채널 종료 감지: 정상 종료(SCENARIO_COMPLETED) vs 비정상 종료
            activeChannel.closeFuture().addListener((ChannelFutureListener) closeFuture -> onChannelClosed());
        });
    }

    /**
     * 외부(Spring Lifecycle)에서 강제 종료를 요청합니다.
     * stopped=true로 설정하여 재연결을 차단하고, 현재 채널을 닫습니다.
     */
    public void stop() {
        stopped = true;
        Channel ch = activeChannel;
        if (ch != null) {
            log.info(StructuredLog.event("active_stopping",
                    "eqpId", eqp.getEqpId(),
                    "connId", ch.id().asShortText()));
            ch.close().syncUninterruptibly();
        }
    }

    // ─── 내부 로직 ───────────────────────────────────────────────────

    /**
     * 채널 close 이벤트 처리.
     * - CLOSE_REASON 속성으로 정상/비정상 종료를 구분합니다.
     * - 정상 종료(SCENARIO_COMPLETED): stopped=true, 재연결 없음
     * - 비정상 종료: backoff 재연결 예약
     */
    private void onChannelClosed() {
        if (stopped) return;

        Channel ch = activeChannel;
        String closeReason = (ch != null) ? ch.attr(ChannelAttributes.CLOSE_REASON).get() : null;

        if (ChannelAttributes.CLOSE_REASON_SCENARIO_COMPLETED.equals(closeReason)) {
            stopped = true;
            log.info(StructuredLog.event("active_closed_no_reconnect",
                    "eqpId", eqp.getEqpId(),
                    "connId", ch != null ? ch.id().asShortText() : "unknown",
                    "closeReason", closeReason));
            return;
        }

        // 비정상 close → 재연결 예약
        scheduleReconnect("channel_closed");
    }

    /**
     * backoff 정책으로 재연결을 예약합니다.
     * - 지연 시간: initialSec * multiplier^(attempt-1), 최대 maxSec
     * - Netty EventLoop(group.next())에서 스케줄링합니다.
     *
     * @param reason 재연결 원인 (로그용)
     */
    private void scheduleReconnect(String reason) {
        reconnectAttempt++;
        long delaySec = computeBackoffDelaySec(
                reconnectAttempt,
                backoffProps.getInitialSec(),
                backoffProps.getMaxSec(),
                backoffProps.getMultiplier()
        );

        log.warn(StructuredLog.event("active_reconnect_scheduled",
                "eqpId", eqp.getEqpId(),
                "reason", reason,
                "attempt", reconnectAttempt,
                "delaySec", delaySec));

        group.next().schedule(this::connectNow, delaySec, TimeUnit.SECONDS);
    }

    /**
     * 지수 backoff 지연 시간을 계산합니다.
     *
     * 공식: ceil(initialSec * multiplier^(attempt-1)), 최대 maxSec
     *
     * @param attempt    현재 시도 횟수 (1부터 시작)
     * @param initialSec 초기 지연(초)
     * @param maxSec     최대 지연(초)
     * @param multiplier 배수
     * @return 지연 시간(초), 최소 1초
     */
    private static long computeBackoffDelaySec(long attempt, long initialSec, long maxSec, double multiplier) {
        double pow = Math.pow(multiplier, Math.max(0, attempt - 1));
        long delay = (long) Math.ceil(initialSec * pow);
        delay = Math.max(1L, delay);
        return Math.min(delay, maxSec);
    }
}
package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EndpointsProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import com.nori.tc.eqpsim.socket.runtime.HostPort;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NettyTransportLifecycle
 *
 * 역할:
 * - Spring SmartLifecycle 구현체로, Spring 기동/종료 시 Netty 전송 계층을 관리합니다.
 * - PASSIVE(서버 bind) 및 ACTIVE(클라이언트 connect)를 모두 처리합니다.
 *
 * 의존성:
 * - EqpRuntimeRegistry: EQP 런타임 정보 및 PASSIVE pool 관리
 * - ScenarioRegistry:   시나리오 plan 조회
 * - ScenarioCompletionTracker: 완료/open-close 추적 → 프로세스 종료 판단
 *
 * 변경 이력:
 * - ActiveClientConnector가 별도 파일로 분리되었습니다.
 * - PassiveChildInitializer도 별도 내부 클래스에서 메서드로 단순화하였습니다.
 */
@SuppressWarnings("deprecation")
public class NettyTransportLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyTransportLifecycle.class);

    private final EqpRuntimeRegistry registry;
    private final EndpointsProperties.ActiveBackoffProperties activeBackoffProps;
    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioCompletionTracker tracker;

    // ─── Netty 리소스 ────────────────────────────────────────────────

    /** PASSIVE accept 전용 그룹 (보통 1 스레드) */
    private EventLoopGroup bossGroup;

    /** PASSIVE child/ACTIVE I/O 처리 그룹 */
    private EventLoopGroup workerGroup;

    // ─── 런타임 상태 ─────────────────────────────────────────────────

    /** endpointId → PASSIVE 서버 채널 (bind된 ServerChannel) */
    private final Map<String, Channel> passiveServerChannelById = new LinkedHashMap<>();

    /** endpointId → PASSIVE 현재 연결 수 카운터 (ConnectionLimitHandler 공유) */
    private final Map<String, AtomicInteger> passiveConnectionCounterById = new ConcurrentHashMap<>();

    /** eqpId → ACTIVE 커넥터 */
    private final Map<String, ActiveClientConnector> activeConnectorById = new LinkedHashMap<>();

    private volatile boolean running = false;

    // ─── 생성자 ─────────────────────────────────────────────────────

    public NettyTransportLifecycle(EqpRuntimeRegistry registry,
                                   EndpointsProperties.ActiveBackoffProperties activeBackoffProps,
                                   ScenarioRegistry scenarioRegistry,
                                   ScenarioCompletionTracker tracker) {
        this.registry = registry;
        this.activeBackoffProps = activeBackoffProps;
        this.scenarioRegistry = scenarioRegistry;
        this.tracker = (tracker == null) ? ScenarioCompletionTracker.NOOP : tracker;
    }

    // ─── SmartLifecycle ──────────────────────────────────────────────

    @Override
    public void start() {
        if (running) return;

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        startPassiveServers();
        startActiveClients();

        running = true;

        log.info(StructuredLog.event("transport_started",
                "passiveServerCount", passiveServerChannelById.size(),
                "activeClientCount", activeConnectorById.size()));
    }

    @Override
    public void stop() {
        if (!running) return;

        log.info(StructuredLog.event("transport_stopping"));

        stopActiveClients();
        stopPassiveServers();
        shutdownEventLoopGroups();

        running = false;

        log.info(StructuredLog.event("transport_stopped"));
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    // ─── PASSIVE 서버 시작/종료 ──────────────────────────────────────

    /**
     * 설정에 정의된 PASSIVE endpoint를 순서대로 bind합니다.
     * bind 실패 시 예외를 throw하여 애플리케이션 기동을 중단합니다.
     */
    private void startPassiveServers() {
        for (Map.Entry<String, HostPort> entry : registry.getPassiveBindById().entrySet()) {
            String endpointId = entry.getKey();
            HostPort bindAddress = entry.getValue();
            int maxConn = registry.getPassiveMaxConnById().getOrDefault(endpointId, 20);

            AtomicInteger connectionCounter = new AtomicInteger(0);
            passiveConnectionCounterById.put(endpointId, connectionCounter);

            ServerBootstrap serverBootstrap = buildPassiveServerBootstrap(endpointId, maxConn, connectionCounter);

            ChannelFuture bindFuture = serverBootstrap.bind(bindAddress.host(), bindAddress.port()).syncUninterruptibly();
            if (!bindFuture.isSuccess()) {
                throw new IllegalStateException(
                        "PASSIVE endpoint bind 실패: endpointId=" + endpointId
                        + " address=" + bindAddress.host() + ":" + bindAddress.port(),
                        bindFuture.cause());
            }

            passiveServerChannelById.put(endpointId, bindFuture.channel());

            log.info(StructuredLog.event("passive_bind_started",
                    "endpointId", endpointId,
                    "bind", bindAddress.host() + ":" + bindAddress.port(),
                    "maxConn", maxConn));
        }
    }

    /**
     * PASSIVE 서버 Bootstrap을 구성합니다.
     * child pipeline은 PassiveChildInitializer로 초기화됩니다.
     */
    private ServerBootstrap buildPassiveServerBootstrap(String endpointId,
                                                        int maxConn,
                                                        AtomicInteger connectionCounter) {
        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new PassiveChildInitializer(endpointId, maxConn, connectionCounter))
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
    }

    /** 모든 PASSIVE 서버 채널을 close합니다. */
    private void stopPassiveServers() {
        for (Map.Entry<String, Channel> entry : passiveServerChannelById.entrySet()) {
            try {
                entry.getValue().close().syncUninterruptibly();
            } catch (Exception ex) {
                log.warn(StructuredLog.event("passive_close_failed",
                        "endpointId", entry.getKey()), ex);
            }
        }
        passiveServerChannelById.clear();
    }

    // ─── ACTIVE 클라이언트 시작/종료 ─────────────────────────────────

    /**
     * 설정에 정의된 ACTIVE EQP를 순서대로 connect합니다.
     * 각 EQP별로 ActiveClientConnector를 생성하고 즉시 connect를 시작합니다.
     */
    private void startActiveClients() {
        for (EqpRuntime eqp : registry.getActiveEqps()) {
            ActiveClientConnector connector = new ActiveClientConnector(
                    eqp,
                    workerGroup,
                    activeBackoffProps,
                    scenarioRegistry,
                    tracker
            );
            activeConnectorById.put(eqp.getEqpId(), connector);
            connector.connectNow();
        }
    }

    /** 모든 ACTIVE 커넥터를 순서대로 stop합니다. */
    private void stopActiveClients() {
        for (ActiveClientConnector connector : activeConnectorById.values()) {
            try {
                connector.stop();
            } catch (Exception ex) {
                log.warn(StructuredLog.event("active_stop_failed",
                        "eqpId", connector.getEqpId()), ex);
            }
        }
        activeConnectorById.clear();
    }

    /** Boss/Worker EventLoopGroup을 graceful shutdown합니다. */
    private void shutdownEventLoopGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
    }

    // ─── PASSIVE Child Pipeline 초기화 ───────────────────────────────

    /**
     * PassiveChildInitializer
     *
     * 역할:
     * - TC가 accept될 때 child 채널의 pipeline을 구성합니다.
     * - 구성 순서: ConnectionLimitHandler → PassiveBindAndFramerHandler
     *   (PassiveBindAndFramerHandler 내부에서 framer/handshake/eqpLifecycle을 추가합니다)
     */
    private final class PassiveChildInitializer extends ChannelInitializer<SocketChannel> {

        private final String endpointId;
        private final int maxConn;
        private final AtomicInteger connectionCounter;

        private PassiveChildInitializer(String endpointId, int maxConn, AtomicInteger connectionCounter) {
            this.endpointId = endpointId;
            this.maxConn = maxConn;
            this.connectionCounter = connectionCounter;
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            // 1) 최대 연결 수 제한: 초과 시 accept 후 즉시 close
            ch.pipeline().addLast("connLimit",
                    new ConnectionLimitHandler(maxConn, connectionCounter));

            // 2) PASSIVE EQP 할당 + framer/handshake/eqpLifecycle 동적 추가
            ch.pipeline().addLast("passiveBind",
                    new PassiveBindAndFramerHandler(endpointId, registry, scenarioRegistry, tracker));
        }
    }
}
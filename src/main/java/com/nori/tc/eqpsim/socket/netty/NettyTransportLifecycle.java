package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.config.EndpointsProperties;
import com.nori.tc.eqpsim.socket.lifecycle.ScenarioCompletionTracker;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntimeRegistry;
import com.nori.tc.eqpsim.socket.runtime.HostPort;
import com.nori.tc.eqpsim.socket.scenario.ScenarioRegistry;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class NettyTransportLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyTransportLifecycle.class);

    private final EqpRuntimeRegistry registry;
    private final EndpointsProperties.ActiveBackoffProperties activeBackoffProps;
    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioCompletionTracker tracker;

    private final Map<String, Channel> passiveServerChannels = new LinkedHashMap<>();
    private final Map<String, AtomicInteger> passiveConnCounters = new ConcurrentHashMap<>();
    private final Map<String, ActiveClientConnector> activeConnectors = new LinkedHashMap<>();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    private volatile boolean running = false;

    public NettyTransportLifecycle(EqpRuntimeRegistry registry,
                                  EndpointsProperties.ActiveBackoffProperties activeBackoffProps,
                                  ScenarioRegistry scenarioRegistry,
                                  ScenarioCompletionTracker tracker) {
        this.registry = registry;
        this.activeBackoffProps = activeBackoffProps;
        this.scenarioRegistry = scenarioRegistry;
        this.tracker = (tracker == null) ? ScenarioCompletionTracker.NOOP : tracker;
    }

    @Override
    public void start() {
        if (running) return;

        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();

        startPassiveServers();
        startActiveClients();

        running = true;
        log.info(StructuredLog.event("transport_started",
                "passiveServerCount", passiveServerChannels.size(),
                "activeClientCount", activeConnectors.size()));
    }

    private void startPassiveServers() {
        for (Map.Entry<String, HostPort> e : registry.getPassiveBindById().entrySet()) {
            String endpointId = e.getKey();
            HostPort bind = e.getValue();
            int maxConn = registry.getPassiveMaxConnById().getOrDefault(endpointId, 20);

            AtomicInteger counter = new AtomicInteger(0);
            passiveConnCounters.put(endpointId, counter);

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new PassiveChildInitializer(endpointId, maxConn, counter, registry, scenarioRegistry, tracker))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(bind.host(), bind.port()).syncUninterruptibly();
            if (!f.isSuccess()) {
                throw new IllegalStateException("Failed to bind passive endpoint " + endpointId + " at " + bind, f.cause());
            }
            passiveServerChannels.put(endpointId, f.channel());

            log.info(StructuredLog.event("passive_bind_started",
                    "endpointId", endpointId,
                    "bind", bind.host() + ":" + bind.port(),
                    "maxConn", maxConn));
        }
    }

    private void startActiveClients() {
        for (EqpRuntime eqp : registry.getActiveEqps()) {
            ActiveClientConnector connector = new ActiveClientConnector(
                    eqp,
                    workerGroup,
                    activeBackoffProps,
                    scenarioRegistry,
                    tracker
            );
            activeConnectors.put(eqp.getEqpId(), connector);
            connector.connectNow();
        }
    }

    @Override
    public void stop() {
        if (!running) return;

        log.info(StructuredLog.event("transport_stopping"));

        for (ActiveClientConnector c : activeConnectors.values()) {
            try { c.stop(); } catch (Exception ex) {
                log.warn(StructuredLog.event("active_stop_failed", "eqpId", c.getEqpId()), ex);
            }
        }
        activeConnectors.clear();

        for (Map.Entry<String, Channel> e : passiveServerChannels.entrySet()) {
            try { e.getValue().close().syncUninterruptibly(); } catch (Exception ex) {
                log.warn(StructuredLog.event("passive_close_failed", "endpointId", e.getKey()), ex);
            }
        }
        passiveServerChannels.clear();

        if (bossGroup != null) bossGroup.shutdownGracefully().syncUninterruptibly();
        if (workerGroup != null) workerGroup.shutdownGracefully().syncUninterruptibly();

        running = false;
        log.info(StructuredLog.event("transport_stopped"));
    }

    @Override public boolean isRunning() { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return 0; }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    private static final class PassiveChildInitializer extends ChannelInitializer<SocketChannel> {
        private final String endpointId;
        private final int maxConn;
        private final AtomicInteger counter;
        private final EqpRuntimeRegistry registry;
        private final ScenarioRegistry scenarioRegistry;
        private final ScenarioCompletionTracker tracker;

        private PassiveChildInitializer(String endpointId, int maxConn, AtomicInteger counter,
                                        EqpRuntimeRegistry registry,
                                        ScenarioRegistry scenarioRegistry,
                                        ScenarioCompletionTracker tracker) {
            this.endpointId = endpointId;
            this.maxConn = maxConn;
            this.counter = counter;
            this.registry = registry;
            this.scenarioRegistry = scenarioRegistry;
            this.tracker = tracker;
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast("connLimit", new ConnectionLimitHandler(maxConn, counter));
            ch.pipeline().addLast("passiveBind",
                    new PassiveBindAndFramerHandler(endpointId, registry, scenarioRegistry, tracker));
        }
    }

    private static final class ActiveClientConnector {
        private static final Logger log = LoggerFactory.getLogger(ActiveClientConnector.class);

        private final EqpRuntime eqp;
        private final EventLoopGroup group;
        private final EndpointsProperties.ActiveBackoffProperties backoff;
        private final Bootstrap bootstrap;

        private volatile boolean stopped = false;
        private volatile Channel channel;
        private long attempt = 0;

        private ActiveClientConnector(EqpRuntime eqp,
                                      EventLoopGroup group,
                                      EndpointsProperties.ActiveBackoffProperties backoff,
                                      ScenarioRegistry scenarioRegistry,
                                      ScenarioCompletionTracker tracker) {
            this.eqp = eqp;
            this.group = group;
            this.backoff = backoff;

            // ✅ scenarioRegistry/tracker는 필드로 들고 있지 않고 즉시 handler 생성에만 사용
            this.bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ActiveChannelInitializer(eqp, scenarioRegistry, tracker));
        }

        public String getEqpId() { return eqp.getEqpId(); }

        public void connectNow() {
            if (stopped) return;
            HostPort target = eqp.getEndpointAddress();

            log.info(StructuredLog.event("active_connecting",
                    "eqpId", eqp.getEqpId(),
                    "endpointId", eqp.getEndpointId(),
                    "target", target.host() + ":" + target.port()));

            ChannelFuture f = bootstrap.connect(target.host(), target.port());
            f.addListener((ChannelFutureListener) future -> {
                if (stopped) {
                    if (future.channel() != null) future.channel().close();
                    return;
                }
                if (!future.isSuccess()) {
                    scheduleReconnect("connect_failed");
                    return;
                }

                channel = future.channel();
                attempt = 0;

                log.info(StructuredLog.event("active_connected",
                        "eqpId", eqp.getEqpId(),
                        "connId", channel.id().asShortText(),
                        "remote", String.valueOf(channel.remoteAddress()),
                        "local", String.valueOf(channel.localAddress())));

                channel.closeFuture().addListener((ChannelFutureListener) cf -> {
                    if (stopped) return;

                    String reason = channel.attr(ChannelAttributes.CLOSE_REASON).get();
                    if (ChannelAttributes.CLOSE_REASON_SCENARIO_COMPLETED.equals(reason)) {
                        log.info(StructuredLog.event("active_closed_no_reconnect",
                                "eqpId", eqp.getEqpId(),
                                "connId", channel.id().asShortText(),
                                "closeReason", reason));
                        stopped = true;
                        return;
                    }

                    scheduleReconnect("channel_closed");
                });
            });
        }

        private void scheduleReconnect(String reason) {
            attempt++;
            long delaySec = computeDelaySec(attempt, backoff.getInitialSec(), backoff.getMaxSec(), backoff.getMultiplier());

            log.warn(StructuredLog.event("active_reconnect_scheduled",
                    "eqpId", eqp.getEqpId(),
                    "reason", reason,
                    "attempt", attempt,
                    "delaySec", delaySec));

            group.next().schedule(this::connectNow, delaySec, java.util.concurrent.TimeUnit.SECONDS);
        }

        private static long computeDelaySec(long attempt, long initialSec, long maxSec, double multiplier) {
            double pow = Math.pow(multiplier, Math.max(0, attempt - 1));
            long delay = (long) Math.ceil(initialSec * pow);
            if (delay < 1) delay = 1;
            return Math.min(delay, maxSec);
        }

        public void stop() {
            stopped = true;
            Channel ch = channel;
            if (ch != null) {
                log.info(StructuredLog.event("active_stopping",
                        "eqpId", eqp.getEqpId(),
                        "connId", ch.id().asShortText()));
                ch.close().syncUninterruptibly();
            }
        }
    }
}
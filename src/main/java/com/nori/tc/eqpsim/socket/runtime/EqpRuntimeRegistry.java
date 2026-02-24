package com.nori.tc.eqpsim.socket.runtime;

import com.nori.tc.eqpsim.socket.config.EndpointsProperties;
import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.config.ProfileProperties;
import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;
import com.nori.tc.eqpsim.socket.config.TcEqpSimProperties;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * EqpRuntimeRegistry
 *
 * 목적:
 * - TcEqpSimProperties를 "런타임 관점"으로 검증/해석하여 EqpRuntime을 구성한다.
 * - PASSIVE(listen) 연결에서 사용할 EQP 풀(endpoint별)을 제공한다.
 *
 * 정책(사용자 결정):
 * - PASSIVE 연결은 "연결 생성 시" EQP를 reserve.
 * - 연결 종료 시 EQP는 "즉시 반환" (pool로 복귀).
 */
public final class EqpRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(EqpRuntimeRegistry.class);

    private final Map<String, EqpRuntime> eqpById;

    // PASSIVE endpointId -> available EQP IDs
    private final Map<String, ConcurrentLinkedQueue<String>> passiveAvailableByEndpoint;

    // ACTIVE EQP 목록(고정)
    private final List<EqpRuntime> activeEqps;

    // PASSIVE listen endpointId -> bind address + maxConn
    private final Map<String, HostPort> listenBindById;
    private final Map<String, Integer> listenMaxConnById;

    // ACTIVE connect endpointId -> target address
    private final Map<String, HostPort> connectTargetById;

    // (진단용) endpoint별 ACTIVE EQP 수
    private final Map<String, Integer> activeEqpCountByEndpoint;

    public EqpRuntimeRegistry(TcEqpSimProperties props) {
        Objects.requireNonNull(props, "props must not be null");

        Map<String, SocketTypeProperties> socketTypes = orEmpty(props.getSocketTypes());
        Map<String, ProfileProperties> profiles = orEmpty(props.getProfiles());

        this.listenBindById = new LinkedHashMap<>();
        this.listenMaxConnById = new LinkedHashMap<>();
        this.connectTargetById = new LinkedHashMap<>();

        EndpointsProperties endpoints = Objects.requireNonNull(props.getEndpoints(), "endpoints must not be null");

        for (Map.Entry<String, EndpointsProperties.ListenEndpointProperties> e : orEmpty(endpoints.getListen()).entrySet()) {
            String id = e.getKey();
            EndpointsProperties.ListenEndpointProperties v = e.getValue();
            if (v == null) continue;
            HostPort hp = HostPort.parse(v.getBind());
            listenBindById.put(id, hp);
            listenMaxConnById.put(id, v.getMaxConn());
        }
        for (Map.Entry<String, EndpointsProperties.ConnectEndpointProperties> e : orEmpty(endpoints.getConnect()).entrySet()) {
            String id = e.getKey();
            EndpointsProperties.ConnectEndpointProperties v = e.getValue();
            if (v == null) continue;
            HostPort hp = HostPort.parse(v.getTarget());
            connectTargetById.put(id, hp);
        }

        Map<String, EqpRuntime> eqpTmp = new LinkedHashMap<>();
        Map<String, ConcurrentLinkedQueue<String>> passiveQueueTmp = new ConcurrentHashMap<>();
        List<EqpRuntime> activeTmp = new ArrayList<>();
        Map<String, Integer> activeCountTmp = new LinkedHashMap<>();

        long defaultWait = props.getDefaults().getDefaultWaitTimeoutSec();
        long defaultHs = props.getDefaults().getDefaultHandshakeTimeoutSec();

        for (Map.Entry<String, EqpProperties> entry : orEmpty(props.getEqps()).entrySet()) {
            String eqpId = entry.getKey();
            EqpProperties eqp = entry.getValue();
            if (eqp == null) continue;

            if (eqp.getMode() == null) {
                throw new IllegalStateException("tc.eqpsim.eqps." + eqpId + ".mode is missing");
            }
            requireNotBlank(eqp.getEndpoint(), "tc.eqpsim.eqps." + eqpId + ".endpoint");
            requireNotBlank(eqp.getSocketType(), "tc.eqpsim.eqps." + eqpId + ".socket-type");
            requireNotBlank(eqp.getProfile(), "tc.eqpsim.eqps." + eqpId + ".profile");

            SocketTypeProperties socketType = socketTypes.get(eqp.getSocketType());
            if (socketType == null) {
                throw new IllegalStateException("eqp " + eqpId + " references missing socketType: " + eqp.getSocketType());
            }
            ProfileProperties profile = profiles.get(eqp.getProfile());
            if (profile == null) {
                throw new IllegalStateException("eqp " + eqpId + " references missing profile: " + eqp.getProfile());
            }

            long waitTimeout = (eqp.getWaitTimeoutSec() > 0) ? eqp.getWaitTimeoutSec() : defaultWait;
            long hsTimeout = (eqp.getHandshakeTimeoutSec() > 0) ? eqp.getHandshakeTimeoutSec() : defaultHs;

            HostPort addr;
            int listenMaxConn = 0;

            if (eqp.getMode() == EqpProperties.Mode.PASSIVE) {
                HostPort bind = listenBindById.get(eqp.getEndpoint());
                if (bind == null) {
                    throw new IllegalStateException("PASSIVE eqp " + eqpId + " references missing listen endpoint: " + eqp.getEndpoint());
                }
                addr = bind;
                listenMaxConn = listenMaxConnById.getOrDefault(eqp.getEndpoint(), 20);
                passiveQueueTmp.computeIfAbsent(eqp.getEndpoint(), k -> new ConcurrentLinkedQueue<>()).add(eqpId);
            } else {
                HostPort target = connectTargetById.get(eqp.getEndpoint());
                if (target == null) {
                    throw new IllegalStateException("ACTIVE eqp " + eqpId + " references missing connect endpoint: " + eqp.getEndpoint());
                }
                addr = target;
                activeCountTmp.put(eqp.getEndpoint(), activeCountTmp.getOrDefault(eqp.getEndpoint(), 0) + 1);
            }

            EqpRuntime rt = new EqpRuntime(
                    eqpId,
                    eqp.getMode(),
                    eqp.getEndpoint(),
                    addr,
                    listenMaxConn,
                    socketType,
                    eqp.getProfile(),
                    profile,
                    waitTimeout,
                    hsTimeout,
                    eqp.getVars()
            );

            eqpTmp.put(eqpId, rt);
            if (eqp.getMode() == EqpProperties.Mode.ACTIVE) {
                activeTmp.add(rt);
            }
        }

        this.eqpById = Collections.unmodifiableMap(eqpTmp);
        this.passiveAvailableByEndpoint = passiveQueueTmp;
        this.activeEqps = Collections.unmodifiableList(activeTmp);
        this.activeEqpCountByEndpoint = Collections.unmodifiableMap(activeCountTmp);

        log.info(StructuredLog.event("runtime_registry_ready",
                "eqpCount", eqpById.size(),
                "passiveEndpointCount", passiveAvailableByEndpoint.size(),
                "activeEqpCount", activeEqps.size()));

        for (Map.Entry<String, EndpointsProperties.ConnectEndpointProperties> e : orEmpty(endpoints.getConnect()).entrySet()) {
            String endpointId = e.getKey();
            int configured = (e.getValue() != null) ? e.getValue().getConnCount() : 0;
            int actual = activeEqpCountByEndpoint.getOrDefault(endpointId, 0);
            if (configured > 0 && configured != actual) {
                log.warn(StructuredLog.event("connect_endpoint_mismatch",
                        "endpointId", endpointId,
                        "configuredConnCount", configured,
                        "activeEqpRefCount", actual));
            }
        }
    }

    public Map<String, HostPort> getListenBindById() {
        return listenBindById;
    }

    public Map<String, Integer> getListenMaxConnById() {
        return listenMaxConnById;
    }

    public Map<String, HostPort> getConnectTargetById() {
        return connectTargetById;
    }

    public List<EqpRuntime> getActiveEqps() {
        return activeEqps;
    }

    public EqpRuntime getEqp(String eqpId) {
        return eqpById.get(eqpId);
    }

    public String reservePassiveEqpId(String listenEndpointId) {
        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(listenEndpointId);
        if (q == null) return null;
        return q.poll();
    }

    public void releasePassiveEqpId(String listenEndpointId, String eqpId) {
        if (listenEndpointId == null || eqpId == null) return;
        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(listenEndpointId);
        if (q == null) return;
        q.add(eqpId);
    }

    private static <K, V> Map<K, V> orEmpty(Map<K, V> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }

    private static void requireNotBlank(String v, String name) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException(name + " is blank");
        }
    }
}
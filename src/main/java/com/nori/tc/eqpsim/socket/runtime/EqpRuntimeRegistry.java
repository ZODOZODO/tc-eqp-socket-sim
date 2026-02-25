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

public final class EqpRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(EqpRuntimeRegistry.class);

    private final Map<String, EqpRuntime> eqpById;

    private final Map<String, ConcurrentLinkedQueue<String>> passiveAvailableByEndpoint;
    private final List<EqpRuntime> activeEqps;

    private final Map<String, HostPort> passiveBindById;
    private final Map<String, Integer> passiveMaxConnById;

    private final Map<String, HostPort> activeTargetById;

    private final Map<String, Integer> activeEqpCountByEndpoint;

    public EqpRuntimeRegistry(TcEqpSimProperties props) {
        Objects.requireNonNull(props, "props must not be null");

        Map<String, SocketTypeProperties> socketTypes = orEmpty(props.getSocketTypes());
        Map<String, ProfileProperties> profiles = orEmpty(props.getProfiles());

        this.passiveBindById = new LinkedHashMap<>();
        this.passiveMaxConnById = new LinkedHashMap<>();
        this.activeTargetById = new LinkedHashMap<>();

        EndpointsProperties endpoints = Objects.requireNonNull(props.getEndpoints(), "endpoints must not be null");

        for (Map.Entry<String, EndpointsProperties.PassiveEndpointProperties> e : orEmpty(endpoints.getPassive()).entrySet()) {
            String id = e.getKey();
            EndpointsProperties.PassiveEndpointProperties v = e.getValue();
            if (v == null) continue;

            HostPort hp = HostPort.parse(v.getBind());
            passiveBindById.put(id, hp);
            passiveMaxConnById.put(id, v.getMaxConn());
        }

        for (Map.Entry<String, EndpointsProperties.ActiveEndpointProperties> e : orEmpty(endpoints.getActive()).entrySet()) {
            String id = e.getKey();
            EndpointsProperties.ActiveEndpointProperties v = e.getValue();
            if (v == null) continue;

            HostPort hp = HostPort.parse(v.getTarget());
            activeTargetById.put(id, hp);
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
            int passiveMaxConn = 0;

            if (eqp.getMode() == EqpProperties.Mode.PASSIVE) {
                HostPort bind = passiveBindById.get(eqp.getEndpoint());
                if (bind == null) {
                    throw new IllegalStateException("PASSIVE eqp " + eqpId + " references missing passive endpoint: " + eqp.getEndpoint());
                }
                addr = bind;
                passiveMaxConn = passiveMaxConnById.getOrDefault(eqp.getEndpoint(), 20);
                passiveQueueTmp.computeIfAbsent(eqp.getEndpoint(), k -> new ConcurrentLinkedQueue<>()).add(eqpId);
            } else {
                HostPort target = activeTargetById.get(eqp.getEndpoint());
                if (target == null) {
                    throw new IllegalStateException("ACTIVE eqp " + eqpId + " references missing active endpoint: " + eqp.getEndpoint());
                }
                addr = target;
                activeCountTmp.put(eqp.getEndpoint(), activeCountTmp.getOrDefault(eqp.getEndpoint(), 0) + 1);
            }

            EqpRuntime rt = new EqpRuntime(
                    eqpId,
                    eqp.getMode(),
                    eqp.getEndpoint(),
                    addr,
                    passiveMaxConn,
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

        for (Map.Entry<String, EndpointsProperties.ActiveEndpointProperties> e : orEmpty(endpoints.getActive()).entrySet()) {
            String endpointId = e.getKey();
            int configured = (e.getValue() != null) ? e.getValue().getConnCount() : 0;
            int actual = activeEqpCountByEndpoint.getOrDefault(endpointId, 0);
            if (configured > 0 && configured != actual) {
                log.warn(StructuredLog.event("active_endpoint_mismatch",
                        "endpointId", endpointId,
                        "configuredConnCount", configured,
                        "activeEqpRefCount", actual));
            }
        }
    }

    public int getTotalEqpCount() {
        return eqpById.size();
    }

    public Map<String, HostPort> getPassiveBindById() {
        return passiveBindById;
    }

    public Map<String, Integer> getPassiveMaxConnById() {
        return passiveMaxConnById;
    }

    public Map<String, HostPort> getActiveTargetById() {
        return activeTargetById;
    }

    public List<EqpRuntime> getActiveEqps() {
        return activeEqps;
    }

    public EqpRuntime getEqp(String eqpId) {
        return eqpById.get(eqpId);
    }

    public String reservePassiveEqpId(String passiveEndpointId) {
        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(passiveEndpointId);
        if (q == null) return null;
        return q.poll();
    }

    public void releasePassiveEqpId(String passiveEndpointId, String eqpId) {
        if (passiveEndpointId == null || eqpId == null) return;
        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(passiveEndpointId);
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
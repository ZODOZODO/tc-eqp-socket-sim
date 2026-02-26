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
 * 역할:
 * - 설정(TcEqpSimProperties)에서 EQP 런타임 정보를 구성하여 메모리에 상주시킨다.
 * - PASSIVE: endpoint별 가용 EQP ID 큐 관리 (reserve/release)
 * - ACTIVE:  연결 대상 EQP 목록 제공
 *
 * ✅ [S2 수정] releasePassiveEqpId 중복 반환 방지
 *   - 기존: q.add(eqpId) → 중복 체크 없음 → 동일 eqpId가 두 번 큐에 삽입될 수 있음
 *   - 결과: 같은 EQP가 두 연결에 동시에 할당되는 버그 발생 가능
 *   - 수정: endpoint별 "현재 할당 중인 eqpId Set"을 추가하여 중복 release 차단
 *           reserve 시 Set에 추가, release 시 Set에서 제거 후 큐에 반환
 */
public final class EqpRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(EqpRuntimeRegistry.class);

    private final Map<String, EqpRuntime> eqpById;

    /** PASSIVE endpoint별 가용 EQP ID 큐 */
    private final Map<String, ConcurrentLinkedQueue<String>> passiveAvailableByEndpoint;

    /**
     * ✅ [S2 수정] PASSIVE endpoint별 "현재 할당 중인 eqpId" Set
     * - reserve 시 Set에 추가: 이후 중복 release 감지 가능
     * - release 시 Set에서 제거 성공 시에만 큐에 반환
     */
    private final Map<String, Set<String>> passiveInUseByEndpoint;

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
            passiveBindById.put(id, HostPort.parse(v.getBind()));
            passiveMaxConnById.put(id, v.getMaxConn());
        }

        for (Map.Entry<String, EndpointsProperties.ActiveEndpointProperties> e : orEmpty(endpoints.getActive()).entrySet()) {
            String id = e.getKey();
            EndpointsProperties.ActiveEndpointProperties v = e.getValue();
            if (v == null) continue;
            activeTargetById.put(id, HostPort.parse(v.getTarget()));
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

        // ✅ [S2 수정] endpoint별 "현재 할당 중" Set 초기화 (빈 Set)
        Map<String, Set<String>> inUseTmp = new ConcurrentHashMap<>();
        for (String endpointId : passiveQueueTmp.keySet()) {
            inUseTmp.put(endpointId, ConcurrentHashMap.newKeySet());
        }
        this.passiveInUseByEndpoint = inUseTmp;

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

    // ─── 공개 API ─────────────────────────────────────────────────────────────

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

    /**
     * PASSIVE EQP ID를 pool에서 1개 예약(할당)한다.
     *
     * ✅ [S2 수정] 할당 시 inUse Set에 추가하여 중복 release 감지 기반 마련
     *
     * @param passiveEndpointId endpoint ID
     * @return 할당된 eqpId. 가용 EQP 없으면 null 반환
     */
    public String reservePassiveEqpId(String passiveEndpointId) {
        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(passiveEndpointId);
        if (q == null) return null;

        String eqpId = q.poll();
        if (eqpId == null) return null;

        // [S2 수정] 할당 중 Set에 등록
        Set<String> inUse = passiveInUseByEndpoint.get(passiveEndpointId);
        if (inUse != null) {
            inUse.add(eqpId);
        }

        return eqpId;
    }

    /**
     * PASSIVE EQP ID를 pool에 반환(해제)한다.
     *
     * ✅ [S2 수정] 중복 release 차단
     *   - inUse Set에서 제거 성공한 경우에만 큐에 반환
     *   - 제거 실패(이미 반환됨): 경고 로그 출력 후 무시
     *   - 이를 통해 동일 eqpId가 큐에 두 번 삽입되어 두 연결에 동시 할당되는 버그를 방지
     *
     * @param passiveEndpointId endpoint ID
     * @param eqpId 반환할 EQP ID
     */
    public void releasePassiveEqpId(String passiveEndpointId, String eqpId) {
        if (passiveEndpointId == null || eqpId == null) return;

        ConcurrentLinkedQueue<String> q = passiveAvailableByEndpoint.get(passiveEndpointId);
        if (q == null) return;

        Set<String> inUse = passiveInUseByEndpoint.get(passiveEndpointId);
        if (inUse != null) {
            boolean removed = inUse.remove(eqpId);
            if (!removed) {
                // [S2 수정] 이미 반환된 eqpId → 중복 release 시도 감지
                log.warn(StructuredLog.event("passive_eqp_release_duplicate",
                        "endpointId", passiveEndpointId,
                        "eqpId", eqpId,
                        "reason", "eqpId_not_in_use_set"));
                return; // 큐에 삽입하지 않음 → 중복 할당 방지
            }
        }

        q.add(eqpId); // 정상 반환: 큐에 재삽입
    }

    // ─── 유틸리티 ─────────────────────────────────────────────────────────────

    private static <K, V> Map<K, V> orEmpty(Map<K, V> m) {
        return (m == null) ? Collections.emptyMap() : m;
    }

    private static void requireNotBlank(String v, String name) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException(name + " is blank");
        }
    }
}
package com.nori.tc.eqpsim.socket.runtime;

import com.nori.tc.eqpsim.socket.config.EqpProperties;
import com.nori.tc.eqpsim.socket.config.ProfileProperties;
import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * EqpRuntime
 *
 * 목적:
 * - properties로부터 "검증/해석"된 EQP 런타임 정보를 담는다.
 * - Netty 연결 생성/파이프라인 구성(프레이머 설치) 단계에서 사용한다.
 *
 * 주의:
 * - 시나리오 로딩/실행은 이후 단계(6~)에서 처리한다.
 */
public final class EqpRuntime {

    private final String eqpId;
    private final EqpProperties.Mode mode;          // PASSIVE / ACTIVE
    private final String endpointId;                // L1, C1 ...
    private final HostPort endpointAddress;          // bind or target
    private final int listenMaxConn;                // PASSIVE + listen endpoint일 때만 의미
    private final SocketTypeProperties socketType;   // 프레이밍 규칙
    private final String profileId;                 // scenario_case1 ...
    private final ProfileProperties profile;         // type/file 등
    private final long waitTimeoutSec;
    private final long handshakeTimeoutSec;
    private final Map<String, String> varsLowerKey;  // {var.xxx} 치환용(키 lower normalize)

    public EqpRuntime(
            String eqpId,
            EqpProperties.Mode mode,
            String endpointId,
            HostPort endpointAddress,
            int listenMaxConn,
            SocketTypeProperties socketType,
            String profileId,
            ProfileProperties profile,
            long waitTimeoutSec,
            long handshakeTimeoutSec,
            Map<String, String> varsLowerKey
    ) {
        this.eqpId = requireNotBlank(eqpId, "eqpId");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.endpointId = requireNotBlank(endpointId, "endpointId");
        this.endpointAddress = Objects.requireNonNull(endpointAddress, "endpointAddress must not be null");
        this.listenMaxConn = listenMaxConn;
        this.socketType = Objects.requireNonNull(socketType, "socketType must not be null");
        this.profileId = requireNotBlank(profileId, "profileId");
        this.profile = Objects.requireNonNull(profile, "profile must not be null");
        this.waitTimeoutSec = waitTimeoutSec;
        this.handshakeTimeoutSec = handshakeTimeoutSec;

        Map<String, String> tmp = new LinkedHashMap<>();
        if (varsLowerKey != null) {
            for (Map.Entry<String, String> e : varsLowerKey.entrySet()) {
                if (e.getKey() == null) continue;
                tmp.put(e.getKey().toLowerCase(), e.getValue());
            }
        }
        this.varsLowerKey = Collections.unmodifiableMap(tmp);
    }

    public String getEqpId() {
        return eqpId;
    }

    public EqpProperties.Mode getMode() {
        return mode;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public HostPort getEndpointAddress() {
        return endpointAddress;
    }

    public int getListenMaxConn() {
        return listenMaxConn;
    }

    public SocketTypeProperties getSocketType() {
        return socketType;
    }

    public String getProfileId() {
        return profileId;
    }

    public ProfileProperties getProfile() {
        return profile;
    }

    public long getWaitTimeoutSec() {
        return waitTimeoutSec;
    }

    public long getHandshakeTimeoutSec() {
        return handshakeTimeoutSec;
    }

    public Map<String, String> getVarsLowerKey() {
        return varsLowerKey;
    }

    private static String requireNotBlank(String v, String name) {
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return v.trim();
    }
}
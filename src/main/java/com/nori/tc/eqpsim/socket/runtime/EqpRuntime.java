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
 * endpoint 의미:
 * - mode=PASSIVE: endpointAddress = bind(host:port), passiveMaxConn 의미 있음
 * - mode=ACTIVE : endpointAddress = target(host:port)
 */
public final class EqpRuntime {

    private final String eqpId;
    private final EqpProperties.Mode mode;

    /**
     * endpoints.passive / endpoints.active 의 id
     */
    private final String endpointId;

    /**
     * PASSIVE: bind, ACTIVE: target
     */
    private final HostPort endpointAddress;

    /**
     * PASSIVE에서만 의미 있음
     */
    private final int passiveMaxConn;

    private final SocketTypeProperties socketType;

    private final String profileId;
    private final ProfileProperties profile;

    private final long waitTimeoutSec;
    private final long handshakeTimeoutSec;

    private final Map<String, String> varsLowerKey;

    public EqpRuntime(
            String eqpId,
            EqpProperties.Mode mode,
            String endpointId,
            HostPort endpointAddress,
            int passiveMaxConn,
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
        this.passiveMaxConn = passiveMaxConn;
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

    public int getPassiveMaxConn() {
        return passiveMaxConn;
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
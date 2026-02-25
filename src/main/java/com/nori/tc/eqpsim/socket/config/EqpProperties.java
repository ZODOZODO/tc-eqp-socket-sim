package com.nori.tc.eqpsim.socket.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.eqps.<EQPID>.*
 *
 * 통신 모드(통일):
 * - PASSIVE: 시뮬레이터가 서버(bind/listen/accept), TC가 접속
 * - ACTIVE : 시뮬레이터가 클라이언트(connect), TC로 접속
 *
 * endpoint 참조 규칙(통일):
 * - mode=PASSIVE -> tc.eqpsim.endpoints.passive.<endpointId>
 * - mode=ACTIVE  -> tc.eqpsim.endpoints.active.<endpointId>
 */
public class EqpProperties {

    private Mode mode;

    /**
     * endpoints.passive / endpoints.active 내 id
     */
    private String endpoint;

    private String socketType;
    private String profile;

    private long waitTimeoutSec = 0;
    private long handshakeTimeoutSec = 0;

    private Map<String, String> vars = new LinkedHashMap<>();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getSocketType() {
        return socketType;
    }

    public void setSocketType(String socketType) {
        this.socketType = socketType;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public long getWaitTimeoutSec() {
        return waitTimeoutSec;
    }

    public void setWaitTimeoutSec(long waitTimeoutSec) {
        this.waitTimeoutSec = waitTimeoutSec;
    }

    public long getHandshakeTimeoutSec() {
        return handshakeTimeoutSec;
    }

    public void setHandshakeTimeoutSec(long handshakeTimeoutSec) {
        this.handshakeTimeoutSec = handshakeTimeoutSec;
    }

    public Map<String, String> getVars() {
        return vars;
    }

    public void setVars(Map<String, String> vars) {
        this.vars = vars;
    }

    public enum Mode {
        PASSIVE,
        ACTIVE
    }
}
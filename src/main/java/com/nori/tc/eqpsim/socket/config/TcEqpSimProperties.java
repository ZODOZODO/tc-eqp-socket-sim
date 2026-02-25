package com.nori.tc.eqpsim.socket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.*
 *
 * 용어/매핑(통일):
 * - PASSIVE: 시뮬레이터 서버(accept)
 * - ACTIVE : 시뮬레이터 클라이언트(connect)
 *
 * endpoints 키(통일):
 * - tc.eqpsim.endpoints.passive
 * - tc.eqpsim.endpoints.active
 * - tc.eqpsim.endpoints.active-backoff
 */
@ConfigurationProperties(prefix = "tc.eqpsim")
public class TcEqpSimProperties {

    private Defaults defaults = new Defaults();

    private Map<String, SocketTypeProperties> socketTypes = new LinkedHashMap<>();

    private EndpointsProperties endpoints = new EndpointsProperties();

    private Map<String, ProfileProperties> profiles = new LinkedHashMap<>();

    private Map<String, EqpProperties> eqps = new LinkedHashMap<>();

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public Map<String, SocketTypeProperties> getSocketTypes() {
        return socketTypes;
    }

    public void setSocketTypes(Map<String, SocketTypeProperties> socketTypes) {
        this.socketTypes = socketTypes;
    }

    public EndpointsProperties getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(EndpointsProperties endpoints) {
        this.endpoints = endpoints;
    }

    public Map<String, ProfileProperties> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileProperties> profiles) {
        this.profiles = profiles;
    }

    public Map<String, EqpProperties> getEqps() {
        return eqps;
    }

    public void setEqps(Map<String, EqpProperties> eqps) {
        this.eqps = eqps;
    }

    public static class Defaults {
        private long defaultWaitTimeoutSec = 60;
        private long defaultHandshakeTimeoutSec = 60;

        public long getDefaultWaitTimeoutSec() {
            return defaultWaitTimeoutSec;
        }

        public void setDefaultWaitTimeoutSec(long defaultWaitTimeoutSec) {
            this.defaultWaitTimeoutSec = defaultWaitTimeoutSec;
        }

        public long getDefaultHandshakeTimeoutSec() {
            return defaultHandshakeTimeoutSec;
        }

        public void setDefaultHandshakeTimeoutSec(long defaultHandshakeTimeoutSec) {
            this.defaultHandshakeTimeoutSec = defaultHandshakeTimeoutSec;
        }
    }
}
package com.nori.tc.eqpsim.socket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.*
 *
 * 목적:
 * - 시뮬레이터 전체 설정을 하나의 루트로 바인딩한다.
 * - socketType, endpoint, profile, eqp 정의를 모두 포함한다.
 *
 * 용어/매핑(통일):
 * - EQP Mode
 *   - PASSIVE: 설비(시뮬레이터)가 서버로 대기 (listen/accept)
 *   - ACTIVE : 설비(시뮬레이터)가 클라이언트로 접속 (connect)
 *
 * 주의:
 * - "키 스펙"은 이 클래스의 필드 구조가 곧 스펙이다.
 * - 검증(참조 무결성 등)은 다음 단계에서 별도 Validator Bean으로 확정한다.
 */
@ConfigurationProperties(prefix = "tc.eqpsim")
public class TcEqpSimProperties {

    /**
     * 공통 기본값
     */
    private Defaults defaults = new Defaults();

    /**
     * socketType 정의 집합
     * - key: socketTypeId (예: LINE_LF)
     */
    private Map<String, SocketTypeProperties> socketTypes = new LinkedHashMap<>();

    /**
     * endpoint 정의 집합
     * - PASSIVE(EQP 서버)에서 사용할 listen endpoints
     * - ACTIVE(EQP 클라이언트)에서 사용할 connect endpoints
     */
    private EndpointsProperties endpoints = new EndpointsProperties();

    /**
     * profile 정의 집합 (SCENARIO / RATE 등)
     * - key: profileId (예: scenario_case1)
     */
    private Map<String, ProfileProperties> profiles = new LinkedHashMap<>();

    /**
     * EQP(가상 설비) 정의 집합
     * - key: eqpId (예: TEST001)
     */
    private Map<String, EqpProperties> eqps = new LinkedHashMap<>();

    // getters/setters

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

    /**
     * 공통 기본값 그룹
     *
     * - defaultWaitTimeoutSec: WAIT 기본 타임아웃(초)
     * - defaultHandshakeTimeoutSec: INITIALIZE 핸드셰이크 타임아웃(초)
     */
    public static class Defaults {

        /**
         * 시나리오 WAIT 기본 타임아웃(초)
         * - EQP에 개별 waitTimeoutSec가 없으면 이 값을 사용
         */
        private long defaultWaitTimeoutSec = 60;

        /**
         * INITIALIZE 핸드셰이크 기본 타임아웃(초)
         * - EQP에 개별 handshakeTimeoutSec가 없으면 이 값을 사용
         */
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
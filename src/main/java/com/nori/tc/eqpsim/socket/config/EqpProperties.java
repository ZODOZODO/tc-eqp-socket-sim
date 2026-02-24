package com.nori.tc.eqpsim.socket.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.eqps.<EQPID>.*
 *
 * EQP는 "가상 설비 인스턴스" 단위의 설정이다.
 * - EQPID는 곧 이 맵의 key이며, 시나리오 치환 {eqpid}에 사용된다.
 * - 시나리오 WAIT timeout/핸드셰이크 timeout은 EQP 단위로 설정(기본 60s).
 *
 * 통신 모드(사용자 결정):
 * - PASSIVE: 시뮬레이터가 서버로 동작 (listen/accept), TC가 접속
 * - ACTIVE : 시뮬레이터가 클라이언트로 동작 (connect), TC에 접속
 *
 * 대소문자 규칙:
 * - CMD 매칭은 "대소문자 무시" -> 런타임에서 upper normalize로 처리한다.
 * - var 키는 바인딩 시점에 lower normalize하여 {var.xxx}로 접근하는 것을 권장한다.
 */
public class EqpProperties {

    /**
     * PASSIVE: 서버(listen) / ACTIVE: 클라이언트(connect)
     */
    private Mode mode;

    /**
     * endpoints.listen / endpoints.connect에 정의된 endpoint id
     * - mode=PASSIVE이면 endpoints.listen.<id>를 참조
     * - mode=ACTIVE면 endpoints.connect.<id>를 참조
     */
    private String endpoint;

    /**
     * socketTypes에 정의된 socketType id
     */
    private String socketType;

    /**
     * profiles에 정의된 profile id
     */
    private String profile;

    /**
     * 시나리오 WAIT 타임아웃(초)
     * - 0 또는 미지정이면 tc.eqpsim.defaults.defaultWaitTimeoutSec 사용
     */
    private long waitTimeoutSec = 0;

    /**
     * INITIALIZE 핸드셰이크 타임아웃(초)
     * - 0 또는 미지정이면 tc.eqpsim.defaults.defaultHandshakeTimeoutSec 사용
     */
    private long handshakeTimeoutSec = 0;

    /**
     * 시나리오 치환에 사용하는 변수 집합
     * - 예: {var.lotid}, {var.stepid}
     */
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
        /**
         * PASSIVE: 서버(listen)로 대기.
         * - TC가 client로 접속해 온다.
         */
        PASSIVE,

        /**
         * ACTIVE: 클라이언트(connect)로 TC에 접속.
         */
        ACTIVE
    }
}
package com.nori.tc.eqpsim.socket.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.endpoints.*
 *
 * 용어 통일(사용자 요청):
 * - PASSIVE: 시뮬레이터가 서버(bind/listen/accept)
 * - ACTIVE : 시뮬레이터가 클라이언트(connect)
 *
 * 설정 키:
 * - tc.eqpsim.endpoints.passive.<id>.bind
 * - tc.eqpsim.endpoints.passive.<id>.max-conn
 * - tc.eqpsim.endpoints.active.<id>.target
 * - tc.eqpsim.endpoints.active.<id>.conn-count
 * - tc.eqpsim.endpoints.active-backoff.*
 */
public class EndpointsProperties {

    /**
     * tc.eqpsim.endpoints.passive.<id>.*
     */
    private Map<String, PassiveEndpointProperties> passive = new LinkedHashMap<>();

    /**
     * tc.eqpsim.endpoints.active.<id>.*
     */
    private Map<String, ActiveEndpointProperties> active = new LinkedHashMap<>();

    /**
     * ACTIVE 재연결 백오프 설정
     * - tc.eqpsim.endpoints.active-backoff.*
     */
    private ActiveBackoffProperties activeBackoff = new ActiveBackoffProperties();

    public Map<String, PassiveEndpointProperties> getPassive() {
        return passive;
    }

    public void setPassive(Map<String, PassiveEndpointProperties> passive) {
        this.passive = passive;
    }

    public Map<String, ActiveEndpointProperties> getActive() {
        return active;
    }

    public void setActive(Map<String, ActiveEndpointProperties> active) {
        this.active = active;
    }

    public ActiveBackoffProperties getActiveBackoff() {
        return activeBackoff;
    }

    public void setActiveBackoff(ActiveBackoffProperties activeBackoff) {
        this.activeBackoff = activeBackoff;
    }

    /**
     * PASSIVE endpoint 설정(서버 bind)
     */
    public static class PassiveEndpointProperties {

        /**
         * bind 주소: "0.0.0.0:31001"
         */
        private String bind;

        /**
         * 포트당 최대 연결 수 (초과 시 accept 후 즉시 close)
         */
        private int maxConn = 20;

        public String getBind() {
            return bind;
        }

        public void setBind(String bind) {
            this.bind = bind;
        }

        public int getMaxConn() {
            return maxConn;
        }

        public void setMaxConn(int maxConn) {
            this.maxConn = maxConn;
        }
    }

    /**
     * ACTIVE endpoint 설정(클라이언트 connect)
     */
    public static class ActiveEndpointProperties {

        /**
         * target 주소: "127.0.0.1:32001"
         */
        private String target;

        /**
         * 이 target으로 생성할 연결 수
         * - 현재 구현에서는 "EQP 수만큼" 연결을 생성하며,
         *   connCount는 진단/검증용으로만 경고를 낼 수 있다.
         */
        private int connCount = 20;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public int getConnCount() {
            return connCount;
        }

        public void setConnCount(int connCount) {
            this.connCount = connCount;
        }
    }

    /**
     * ACTIVE 재연결 백오프 설정
     */
    public static class ActiveBackoffProperties {

        private long initialSec = 1;
        private long maxSec = 30;
        private double multiplier = 2.0;

        public long getInitialSec() {
            return initialSec;
        }

        public void setInitialSec(long initialSec) {
            this.initialSec = initialSec;
        }

        public long getMaxSec() {
            return maxSec;
        }

        public void setMaxSec(long maxSec) {
            this.maxSec = maxSec;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }
    }
}
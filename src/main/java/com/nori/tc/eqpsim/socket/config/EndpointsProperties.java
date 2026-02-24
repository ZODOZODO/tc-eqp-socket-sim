package com.nori.tc.eqpsim.socket.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * tc.eqpsim.endpoints.*
 *
 * - LISTEN: 시뮬레이터가 서버로 포트를 열고 TC가 접속
 * - CONNECT: 시뮬레이터가 클라이언트로 TC에 접속
 */
public class EndpointsProperties {

    /**
     * tc.eqpsim.endpoints.listen.<id>.*
     */
    private Map<String, ListenEndpointProperties> listen = new LinkedHashMap<>();

    /**
     * tc.eqpsim.endpoints.connect.<id>.*
     */
    private Map<String, ConnectEndpointProperties> connect = new LinkedHashMap<>();

    /**
     * CONNECT 재연결 정책(지수 백오프) - 사용자 결정(17-B)
     */
    private ConnectBackoffProperties connectBackoff = new ConnectBackoffProperties();

    public Map<String, ListenEndpointProperties> getListen() {
        return listen;
    }

    public void setListen(Map<String, ListenEndpointProperties> listen) {
        this.listen = listen;
    }

    public Map<String, ConnectEndpointProperties> getConnect() {
        return connect;
    }

    public void setConnect(Map<String, ConnectEndpointProperties> connect) {
        this.connect = connect;
    }

    public ConnectBackoffProperties getConnectBackoff() {
        return connectBackoff;
    }

    public void setConnectBackoff(ConnectBackoffProperties connectBackoff) {
        this.connectBackoff = connectBackoff;
    }

    /**
     * LISTEN endpoint 설정
     */
    public static class ListenEndpointProperties {

        /**
         * bind 주소: "0.0.0.0:31001" 형태
         */
        private String bind;

        /**
         * 포트당 최대 연결 수
         * - 사용자 결정(21-B): "초과 시 즉시 close" 방식으로 제한
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
     * CONNECT endpoint 설정
     */
    public static class ConnectEndpointProperties {

        /**
         * target 주소: "127.0.0.1:32001" 형태
         */
        private String target;

        /**
         * 이 target으로 생성할 연결 수
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
     * CONNECT 재연결 백오프 설정
     * - initialSec: 최초 재시도 간격(초)
     * - maxSec: 최대 간격(초)
     * - multiplier: 증가 배수
     */
    public static class ConnectBackoffProperties {

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
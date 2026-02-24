package com.nori.tc.eqpsim.socket.runtime;

import java.util.Objects;

/**
 * "host:port" 형태의 주소를 런타임에서 표준화하기 위한 값 객체.
 *
 * 예:
 * - "0.0.0.0:31001"
 * - "127.0.0.1:32001"
 */
public record HostPort(String host, int port) {

    public HostPort {
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host is blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    public static HostPort parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("address is blank");
        }
        String v = s.trim();
        int idx = v.lastIndexOf(':');
        if (idx <= 0 || idx == v.length() - 1) {
            throw new IllegalArgumentException("address must be host:port, but was: " + s);
        }
        String host = v.substring(0, idx).trim();
        int port;
        try {
            port = Integer.parseInt(v.substring(idx + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port in address: " + s, e);
        }
        return new HostPort(host, port);
    }
}
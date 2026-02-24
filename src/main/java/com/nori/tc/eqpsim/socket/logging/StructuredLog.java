package com.nori.tc.eqpsim.socket.logging;

import java.util.Locale;

/**
 * StructuredLog
 *
 * 목적:
 * - 로그 메시지를 "key=value" 형태로 통일한다.
 *
 * 규칙:
 * - event는 반드시 포함할 것을 권장한다: event=...
 * - 값에 공백/따옴표/백슬래시/= 등이 포함되면 "..."로 감싸고 escape 한다.
 *
 * 예:
 * - StructuredLog.event("handshake_start", "eqpId", "TEST001", "timeoutSec", 60)
 *   -> event=handshake_start eqpId=TEST001 timeoutSec=60
 */
public final class StructuredLog {

    private StructuredLog() {
        // utility class
    }

    public static String event(String event, Object... kv) {
        StringBuilder sb = new StringBuilder(128);
        appendPair(sb, "event", event);
        appendPairs(sb, kv);
        return sb.toString();
    }

    public static String kv(Object... kv) {
        StringBuilder sb = new StringBuilder(128);
        appendPairs(sb, kv);
        return sb.toString().trim();
    }

    private static void appendPairs(StringBuilder sb, Object... kv) {
        if (kv == null || kv.length == 0) {
            return;
        }
        if (kv.length % 2 != 0) {
            // 실수 방지: 홀수면 마지막을 무시
            int len = kv.length - 1;
            Object[] trimmed = new Object[len];
            System.arraycopy(kv, 0, trimmed, 0, len);
            kv = trimmed;
        }
        for (int i = 0; i < kv.length; i += 2) {
            String key = String.valueOf(kv[i]);
            Object val = kv[i + 1];
            appendPair(sb, key, val);
        }
    }

    private static void appendPair(StringBuilder sb, String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(key);
        sb.append('=');
        sb.append(encodeValue(value));
    }

    private static String encodeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value);

        // 공백/제어문자/따옴표/백슬래시/= 포함 시 quoted
        boolean needQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\\' || c == '=' || c < 0x20) {
                needQuote = true;
                break;
            }
        }
        if (!needQuote) {
            return s;
        }

        // escape
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                out.append("\\\"");
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }
}
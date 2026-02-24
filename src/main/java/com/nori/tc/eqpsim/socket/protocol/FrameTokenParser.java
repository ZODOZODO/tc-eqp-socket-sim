package com.nori.tc.eqpsim.socket.protocol;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FrameTokenParser
 *
 * 목적
 * - 프레임 문자열(UTF-8로 디코딩된 payload)에서 "토큰(NAME=VALUE)"를 파싱한다.
 * - 특히, WAIT/Handshake에서 필요한 CMD를 빠르게 추출한다.
 *
 * 입력 규칙(사용자 확정)
 * - 토큰은 공백(whitespace)으로 구분한다.
 * - 각 토큰은 "NAME=VALUE" 형태다.
 * - NAME/VALUE의 대소문자는 의미가 없으므로 비교 시 upper normalize 한다.
 *
 * 주의
 * - 토큰 구분 공백이 "완전히 0"인 형태(CMD=...EQPID=...처럼 붙음)는 파싱이 불가능하다.
 * - 따라서 토큰 간 최소 1개 이상의 공백이 반드시 필요하다.
 */
public final class FrameTokenParser {

    private FrameTokenParser() {
        // utility class
    }

    /**
     * 프레임에서 CMD 값을 추출하여 "대문자(upper)"로 반환한다.
     *
     * @param frameUtf8 프레임 문자열(UTF-8 디코딩 결과)
     * @return CMD 값(upper) 또는 CMD 토큰이 없으면 null
     */
    public static String extractCmdUpper(String frameUtf8) {
        if (frameUtf8 == null || frameUtf8.isBlank()) {
            return null;
        }

        // 공백 분리를 regex로 하지 않고 수동 스캔하여 불필요한 객체 생성 최소화
        final int len = frameUtf8.length();
        int i = 0;

        while (i < len) {
            // 1) 선행 공백 skip
            while (i < len && Character.isWhitespace(frameUtf8.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }

            // 2) 토큰 끝(공백)까지 전진
            int tokenStart = i;
            while (i < len && !Character.isWhitespace(frameUtf8.charAt(i))) {
                i++;
            }
            int tokenEnd = i; // [tokenStart, tokenEnd)

            // 3) 토큰 처리
            String token = frameUtf8.substring(tokenStart, tokenEnd);
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                // '='가 없거나, NAME이 비었거나, VALUE가 비었으면(마지막이 '=') 무시
                // (VALUE empty를 허용하고 싶다면 eq==len-1 케이스를 처리하면 됨)
                continue;
            }

            String name = token.substring(0, eq).trim();
            if (name.isEmpty()) {
                continue;
            }

            // NAME은 대소문자 무시 정책 -> upper로 normalize
            String nameUpper = name.toUpperCase(Locale.ROOT);
            if (!"CMD".equals(nameUpper)) {
                continue;
            }

            String value = token.substring(eq + 1).trim();
            if (value.isEmpty()) {
                // CMD= 형태를 허용하지 않음(현재는 무시)
                return null;
            }

            // CMD 값도 대소문자 무시 정책 -> upper로 normalize
            return value.toUpperCase(Locale.ROOT);
        }

        return null;
    }

    /**
     * 프레임을 NAME=VALUE 맵으로 파싱한다.
     *
     * 정책
     * - Key(NAME): upper normalize(대소문자 무시)
     * - Value: trim만 수행(원문 보존 필요 시 여기에서 정책 변경 가능)
     * - 동일 NAME이 여러 번 나오면 "마지막 값"이 덮어쓴다.
     *
     * @param frameUtf8 프레임 문자열(UTF-8 디코딩 결과)
     * @return upper key map (비어있을 수 있음)
     */
    public static Map<String, String> parseToUpperKeyMap(String frameUtf8) {
        Map<String, String> out = new LinkedHashMap<>();
        if (frameUtf8 == null || frameUtf8.isBlank()) {
            return out;
        }

        final int len = frameUtf8.length();
        int i = 0;

        while (i < len) {
            while (i < len && Character.isWhitespace(frameUtf8.charAt(i))) {
                i++;
            }
            if (i >= len) {
                break;
            }

            int tokenStart = i;
            while (i < len && !Character.isWhitespace(frameUtf8.charAt(i))) {
                i++;
            }
            int tokenEnd = i;

            String token = frameUtf8.substring(tokenStart, tokenEnd);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                // '=' 없음 또는 NAME이 빈 경우는 무시
                continue;
            }

            String name = token.substring(0, eq).trim();
            if (name.isEmpty()) {
                continue;
            }

            // VALUE는 '=' 이후 전체를 사용(값에 '='가 들어갈 수 있으면 여기 방식이 안전)
            String value = (eq < token.length() - 1) ? token.substring(eq + 1).trim() : "";

            out.put(name.toUpperCase(Locale.ROOT), value);
        }

        return out;
    }
}
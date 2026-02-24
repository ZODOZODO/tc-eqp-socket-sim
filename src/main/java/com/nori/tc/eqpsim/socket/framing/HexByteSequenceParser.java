package com.nori.tc.eqpsim.socket.framing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * startHex/endHex 같은 설정 문자열을 byte[]로 변환합니다.
 *
 * 허용 입력 예:
 * - "02"
 * - "0x02"
 * - "02 03"
 * - "0x02 0x03"
 * - "02,03"
 * - "0203"      (짝수 길이인 경우 2자리씩 끊어 바이트로 변환)
 *
 * 주의:
 * - 비정상 입력은 IllegalArgumentException으로 fail-fast합니다.
 */
public final class HexByteSequenceParser {

    private HexByteSequenceParser() {
        // utility class
    }

    public static byte[] parseHexSequence(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("hex sequence input is blank");
        }

        // 구분자(콤마)와 연속 공백을 정리
        String normalized = input
                .replace(",", " ")
                .trim();

        // 공백 기준 토큰화
        String[] tokens = normalized.split("\\s+");

        List<Byte> bytes = new ArrayList<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }

            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith("0x")) {
                t = t.substring(2);
            }

            // "0203" 형태(짝수 길이, 2바이트 이상) 지원
            if (t.length() > 2 && (t.length() % 2 == 0) && isAllHex(t)) {
                for (int i = 0; i < t.length(); i += 2) {
                    String part = t.substring(i, i + 2);
                    bytes.add(parseOneByte(part));
                }
                continue;
            }

            // 기본은 1바이트 토큰(1~2 hex)
            if (!isAllHex(t) || t.length() > 2) {
                throw new IllegalArgumentException("invalid hex token: " + token);
            }
            if (t.length() == 1) {
                t = "0" + t;
            }
            bytes.add(parseOneByte(t));
        }

        if (bytes.isEmpty()) {
            throw new IllegalArgumentException("no hex bytes parsed from input: " + input);
        }

        byte[] out = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            out[i] = bytes.get(i);
        }
        return out;
    }

    private static boolean isAllHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok =
                    (c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte parseOneByte(String twoHexChars) {
        int v = Integer.parseInt(twoHexChars, 16);
        return (byte) (v & 0xFF);
    }
}
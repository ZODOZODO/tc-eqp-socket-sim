package com.nori.tc.eqpsim.socket.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FrameTokenParser 단위 테스트
 */
class FrameTokenParserTests {

    @Test
    void extractCmdUpper_basic() {
        String frame = "CMD=TOOL_CONDITION_REQUEST EQPID=TEST001 LOTID=TESTLOT01";
        assertEquals("TOOL_CONDITION_REQUEST", FrameTokenParser.extractCmdUpper(frame));
    }

    @Test
    void extractCmdUpper_caseInsensitive() {
        String frame = "cmd=initialize eqpid=test001";
        assertEquals("INITIALIZE", FrameTokenParser.extractCmdUpper(frame));
    }

    @Test
    void extractCmdUpper_ignores_unrelated_tokens() {
        String frame = "EQPID=TEST001 LOTID=TESTLOT01";
        assertNull(FrameTokenParser.extractCmdUpper(frame));
    }

    @Test
    void extractCmdUpper_handles_multiple_spaces() {
        String frame = "  CMD=PING    EQPID=TEST001   ";
        assertEquals("PING", FrameTokenParser.extractCmdUpper(frame));
    }

    @Test
    void parseToUpperKeyMap_basic() {
        String frame = "cmd=initialize eqpid=Test001 lotid=TESTLOT01";
        Map<String, String> map = FrameTokenParser.parseToUpperKeyMap(frame);

        assertEquals("initialize", map.get("CMD"));     // value는 trim만, 대소문자 변환은 안 함
        assertEquals("Test001", map.get("EQPID"));
        assertEquals("TESTLOT01", map.get("LOTID"));
    }

    @Test
    void parseToUpperKeyMap_last_wins() {
        String frame = "CMD=A CMD=B";
        Map<String, String> map = FrameTokenParser.parseToUpperKeyMap(frame);
        assertEquals("B", map.get("CMD"));
    }
}

package com.nori.tc.eqpsim.socket.config;

/**
 * tc.eqpsim.socket-types.<id>.*
 *
 * socketType은 "프레이밍 규칙"을 정의한다.
 *
 * 지원 종류(사용자 결정):
 * 1) LINE_END  : LF/CR/CRLF 종단 기반
 * 2) START_END : 시작 바이트 시퀀스 ~ 종료 바이트 시퀀스 기반
 * 3) REGEX     : 정규식 매칭 기반
 *
 * 주의:
 * - 실제 프레이밍 구현(바이트 파싱/정규식 매칭)은 3번 단계(Framer 구현)에서 진행한다.
 * - 여기서는 "설정 스펙"을 명확히 고정한다.
 */
public class SocketTypeProperties {

    /**
     * socketType 종류
     */
    private Kind kind;

    /**
     * kind=LINE_END 일 때 사용
     */
    private LineEnding lineEnding;

    /**
     * kind=START_END 일 때 사용
     * - hex byte sequence 문자열
     * - 예시: "02" / "02 03" / "02,03" / "0x02 0x03"
     * - 실제 파싱 규칙은 Framer 단계에서 고정(토큰 분리 후 0x 제거, hex decode)
     */
    private String startHex;

    /**
     * kind=START_END 일 때 사용
     */
    private String endHex;

    /**
     * kind=REGEX 일 때 사용
     * - UTF-8 문자열 버퍼 기준 정규식으로 프레임을 추출한다.
     * - 반드시 "한 프레임"을 매칭하는 패턴이어야 한다.
     */
    private String regexPattern;

    // getters/setters

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public LineEnding getLineEnding() {
        return lineEnding;
    }

    public void setLineEnding(LineEnding lineEnding) {
        this.lineEnding = lineEnding;
    }

    public String getStartHex() {
        return startHex;
    }

    public void setStartHex(String startHex) {
        this.startHex = startHex;
    }

    public String getEndHex() {
        return endHex;
    }

    public void setEndHex(String endHex) {
        this.endHex = endHex;
    }

    public String getRegexPattern() {
        return regexPattern;
    }

    public void setRegexPattern(String regexPattern) {
        this.regexPattern = regexPattern;
    }

    public enum Kind {
        LINE_END,
        START_END,
        REGEX
    }

    public enum LineEnding {
        LF,     // \n (0x0A)
        CR,     // \r (0x0D)
        CRLF    // \r\n (0x0D 0x0A)
    }
}
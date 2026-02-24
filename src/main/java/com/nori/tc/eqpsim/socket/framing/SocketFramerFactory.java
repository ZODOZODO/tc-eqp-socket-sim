package com.nori.tc.eqpsim.socket.framing;

import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SocketTypeProperties(kind/option)에 따라 적절한 Netty Frame Decoder를 생성합니다.
 *
 * 설계 의도:
 * - "프레이밍"을 Netty pipeline에 꽂을 수 있는 ByteToMessageDecoder로 통일합니다.
 * - LINE_END / START_END / REGEX 3종을 지원합니다.
 *
 * 주의:
 * - REGEX는 UTF-8 문자열 버퍼 기반이며, 패턴이 "프레임 1개"를 매칭하도록 작성되어야 합니다.
 * - 프레임 결과(ByteBuf)는 "프레이밍 바이트를 제거한 payload"만 out에 전달합니다.
 */
public final class SocketFramerFactory {

    private SocketFramerFactory() {
        // utility class
    }

    /**
     * SocketTypeProperties로부터 프레이밍 디코더를 생성합니다.
     *
     * @param props socket type 설정
     * @return Netty ByteToMessageDecoder
     */
    public static ByteToMessageDecoder create(SocketTypeProperties props) {
        Objects.requireNonNull(props, "socketType properties must not be null");
        Objects.requireNonNull(props.getKind(), "socketType.kind must not be null");

        return switch (props.getKind()) {
            case LINE_END -> {
                Objects.requireNonNull(props.getLineEnding(), "socketType.lineEnding must not be null for LINE_END");
                yield new LineEndingFrameDecoder(props.getLineEnding());
            }
            case START_END -> {
                if (isBlank(props.getStartHex()) || isBlank(props.getEndHex())) {
                    throw new IllegalArgumentException("socketType.startHex/endHex must not be blank for START_END");
                }
                byte[] start = HexByteSequenceParser.parseHexSequence(props.getStartHex());
                byte[] end = HexByteSequenceParser.parseHexSequence(props.getEndHex());
                yield new StartEndFrameDecoder(start, end);
            }
            case REGEX -> {
                if (isBlank(props.getRegexPattern())) {
                    throw new IllegalArgumentException("socketType.regexPattern must not be blank for REGEX");
                }
                // UTF-8 문자열 버퍼 기준 정규식
                Pattern pattern = Pattern.compile(props.getRegexPattern());
                yield new RegexFrameDecoder(pattern, StandardCharsets.UTF_8);
            }
        };
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
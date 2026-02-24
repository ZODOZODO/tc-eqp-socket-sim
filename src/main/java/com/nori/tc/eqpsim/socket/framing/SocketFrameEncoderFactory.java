package com.nori.tc.eqpsim.socket.framing;

import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * SocketFrameEncoderFactory
 *
 * 목적:
 * - SocketTypeProperties 기반으로 OUTBOUND 프레임을 구성한다.
 *
 * 정책:
 * - LINE_END : payload + delimiter(LF/CR/CRLF)
 * - START_END: start + payload + end
 * - REGEX    : payload 그대로 (자동 래핑 없음)
 *
 * 주의:
 * - REGEX는 원격이 regex 기반으로 framing을 한다는 전제이므로 payload 그대로를 전송한다.
 */
public final class SocketFrameEncoderFactory {

    private SocketFrameEncoderFactory() {
        // utility class
    }

    public static ByteBuf encodeUtf8(SocketTypeProperties socketType, ByteBufAllocator alloc, String payloadUtf8) {
        Objects.requireNonNull(socketType, "socketType must not be null");
        Objects.requireNonNull(socketType.getKind(), "socketType.kind must not be null");
        Objects.requireNonNull(alloc, "allocator must not be null");
        Objects.requireNonNull(payloadUtf8, "payloadUtf8 must not be null");

        byte[] payload = payloadUtf8.getBytes(StandardCharsets.UTF_8);

        return switch (socketType.getKind()) {
            case LINE_END -> {
                Objects.requireNonNull(socketType.getLineEnding(), "socketType.lineEnding must not be null for LINE_END");
                byte[] delim = switch (socketType.getLineEnding()) {
                    case LF -> new byte[]{(byte) 0x0A};
                    case CR -> new byte[]{(byte) 0x0D};
                    case CRLF -> new byte[]{(byte) 0x0D, (byte) 0x0A};
                };

                ByteBuf out = alloc.buffer(payload.length + delim.length);
                out.writeBytes(payload);
                out.writeBytes(delim);
                yield out;
            }
            case START_END -> {
                if (socketType.getStartHex() == null || socketType.getStartHex().trim().isEmpty()) {
                    throw new IllegalArgumentException("socketType.startHex must not be blank for START_END");
                }
                if (socketType.getEndHex() == null || socketType.getEndHex().trim().isEmpty()) {
                    throw new IllegalArgumentException("socketType.endHex must not be blank for START_END");
                }
                byte[] start = HexByteSequenceParser.parseHexSequence(socketType.getStartHex());
                byte[] end = HexByteSequenceParser.parseHexSequence(socketType.getEndHex());

                ByteBuf out = alloc.buffer(start.length + payload.length + end.length);
                out.writeBytes(start);
                out.writeBytes(payload);
                out.writeBytes(end);
                yield out;
            }
            case REGEX -> {
                // 자동 래핑 없음
                ByteBuf out = alloc.buffer(payload.length);
                out.writeBytes(payload);
                yield out;
            }
        };
    }
}
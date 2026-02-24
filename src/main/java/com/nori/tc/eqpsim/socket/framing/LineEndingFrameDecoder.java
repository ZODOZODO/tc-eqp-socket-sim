package com.nori.tc.eqpsim.socket.framing;

import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.Objects;

/**
 * LINE_END 프레이밍 디코더
 * - LF / CR / CRLF 종단 기준으로 프레임을 분리합니다.
 *
 * 출력(out):
 * - delimiter(종단 바이트)는 제거된 payload만 전달합니다.
 *
 * 예:
 * - 입력: "A\nB\n"
 * - 출력: "A", "B"
 */
public class LineEndingFrameDecoder extends ByteToMessageDecoder {

    private final byte[] delimiter;

    public LineEndingFrameDecoder(SocketTypeProperties.LineEnding ending) {
        Objects.requireNonNull(ending, "line ending must not be null");
        this.delimiter = switch (ending) {
            case LF -> new byte[]{(byte) 0x0A};
            case CR -> new byte[]{(byte) 0x0D};
            case CRLF -> new byte[]{(byte) 0x0D, (byte) 0x0A};
        };
    }

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            int readerIdx = in.readerIndex();
            int writerIdx = in.writerIndex();

            // delimiter가 존재할 수 있는 최소 길이 확보
            if (writerIdx - readerIdx < delimiter.length) {
                return;
            }

            int delimIdx = ByteBufSearch.indexOf(in, readerIdx, writerIdx, delimiter);
            if (delimIdx < 0) {
                // delimiter가 아직 없으면 다음 데이터까지 대기
                return;
            }

            int frameLen = delimIdx - readerIdx;
            ByteBuf frame = in.retainedSlice(readerIdx, frameLen);

            // payload + delimiter 소비
            in.readerIndex(delimIdx + delimiter.length);

            out.add(frame);
            // loop: 남은 데이터에서 다음 프레임 계속 추출
        }
    }
}
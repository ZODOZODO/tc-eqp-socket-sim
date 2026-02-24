package com.nori.tc.eqpsim.socket.framing;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.Objects;

/**
 * START_END 프레이밍 디코더
 * - start 시퀀스 이후, end 시퀀스 이전까지를 1 프레임(payload)로 추출합니다.
 *
 * 출력(out):
 * - start/end 래핑 바이트는 제거된 payload만 전달합니다.
 *
 * 동기화 규칙:
 * - readerIndex 이후에서 start를 찾지 못하면,
 *   "잠재적 start prefix"를 제외한 앞부분을 버려 다음 입력에서 재동기화합니다.
 */
public class StartEndFrameDecoder extends ByteToMessageDecoder {

    private final byte[] start;
    private final byte[] end;

    public StartEndFrameDecoder(byte[] start, byte[] end) {
        this.start = Objects.requireNonNull(start, "start bytes must not be null");
        this.end = Objects.requireNonNull(end, "end bytes must not be null");

        if (start.length == 0 || end.length == 0) {
            throw new IllegalArgumentException("start/end bytes must not be empty");
        }
    }

    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            int readerIdx = in.readerIndex();
            int writerIdx = in.writerIndex();
            int readable = writerIdx - readerIdx;
            if (readable <= 0) {
                return;
            }

            // 1) start 찾기
            int startIdx = ByteBufSearch.indexOf(in, readerIdx, writerIdx, start);
            if (startIdx < 0) {
                // start가 없으면: start가 걸칠 수 있는 마지막 (startLen-1) 바이트만 남기고 discard
                int keep = Math.min(start.length - 1, readable);
                int discard = readable - keep;
                if (discard > 0) {
                    in.readerIndex(readerIdx + discard);
                }
                return;
            }

            // start 이전 쓰레기 데이터 discard
            if (startIdx > readerIdx) {
                in.readerIndex(startIdx);
                readerIdx = startIdx;
                readable = in.writerIndex() - readerIdx;
            }

            // start 시퀀스가 완전히 들어왔는지 확인
            if (readable < start.length) {
                return;
            }

            int payloadStart = readerIdx + start.length;

            // 2) end 찾기 (start 이후부터)
            int endIdx = ByteBufSearch.indexOf(in, payloadStart, writerIdx, end);
            if (endIdx < 0) {
                // end가 아직 없으면 다음 입력 대기
                return;
            }

            int payloadLen = endIdx - payloadStart;
            if (payloadLen < 0) {
                // 논리상 발생하면 안 됨. 안전장치.
                throw new IllegalStateException("invalid payload length in START_END framing");
            }

            ByteBuf frame = in.retainedSlice(payloadStart, payloadLen);

            // start + payload + end 모두 소비
            in.readerIndex(endIdx + end.length);

            out.add(frame);
            // loop: 다음 프레임 계속 추출
        }
    }
}
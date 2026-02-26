package com.nori.tc.eqpsim.socket.framing;

import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RegexFrameDecoder
 *
 * 역할:
 * - UTF-8(또는 지정 Charset)로 ByteBuf 내용을 문자열로 보고 정규식 매칭으로 프레임을 추출한다.
 *
 * ⚠️ 중요 제한:
 * - 문자 인덱스를 바이트 인덱스로 1:1 매핑한다.
 * - ASCII 범위 데이터에서 가장 안전하다.
 * - 비-ASCII(한글 등)가 섞인다면 LINE_END / START_END를 권장한다.
 *
 * ✅ [M4 수정] 버퍼 폭주 방지
 * - 기존: 1KB만 남기고 버림 → 재동기화 불가, 정규식이 매칭되지 않으면 무한 누적
 * - 수정: MAX_BUFFER_BYTES 초과 시 프로토콜 위반으로 간주,
 *         버퍼 전량 폐기 + 채널 close → 명확한 오류 처리
 */
public class RegexFrameDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(RegexFrameDecoder.class);

    /** 버퍼 최대 크기 (초과 시 프로토콜 위반으로 간주) */
    private static final int MAX_BUFFER_BYTES = 256 * 1024; // 256KB

    private final Pattern pattern;
    private final Charset charset;

    public RegexFrameDecoder(Pattern pattern, Charset charset) {
        this.pattern = Objects.requireNonNull(pattern, "pattern must not be null");
        this.charset = Objects.requireNonNull(charset, "charset must not be null");
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

            // ─── [M4 수정] 버퍼 폭주 방지 ──────────────────────────────────────
            // 정규식이 매칭되지 않으면 버퍼가 무한히 쌓인다.
            // 256KB 초과 시 프로토콜 위반으로 간주하고 채널을 종료한다.
            // "1KB만 남기고 버림" 방식은 잘못된 재동기화를 유발하므로 사용하지 않는다.
            if (readable > MAX_BUFFER_BYTES) {
                log.warn(StructuredLog.event("regex_frame_buffer_overflow",
                        "connId", ctx.channel().id().asShortText(),
                        "readable", readable,
                        "maxBytes", MAX_BUFFER_BYTES,
                        "action", "close"));

                in.readerIndex(writerIdx); // 버퍼 전량 폐기
                ctx.close();              // 프로토콜 위반: 채널 종료
                return;
            }

            // 현재 누적 데이터를 문자열로 변환
            String s = in.toString(readerIdx, readable, charset);

            Matcher m = pattern.matcher(s);
            if (!m.find()) {
                // 아직 매칭이 안 되면 다음 입력 대기
                return;
            }

            int matchStart = m.start();
            int matchEnd = m.end();
            if (matchEnd <= matchStart) {
                // 안전장치: 빈 매칭은 무시
                throw new IllegalStateException("regex match has invalid range: start=" + matchStart + " end=" + matchEnd);
            }

            // ASCII 기반(문자 index == 바이트 index) 전제
            int frameAbsStart = readerIdx + matchStart;
            int frameLen = matchEnd - matchStart;

            ByteBuf frame = in.retainedSlice(frameAbsStart, frameLen);

            // prefix + frame 모두 소비
            in.readerIndex(frameAbsStart + frameLen);

            out.add(frame);
            // loop: 남은 데이터에서 계속 추출
        }
    }
}
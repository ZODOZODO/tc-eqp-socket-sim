package com.nori.tc.eqpsim.socket.framing;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REGEX 프레이밍 디코더
 * - UTF-8(또는 지정 Charset)로 ByteBuf 내용을 문자열로 보고 정규식 매칭으로 프레임을 추출합니다.
 *
 * 출력(out):
 * - 정규식이 매칭한 "문자열 구간"을 프레임으로 반환합니다.
 *
 * ⚠️ 중요 제한:
 * - "문자 인덱스"를 ByteBuf의 "바이트 인덱스"로 1:1 매핑합니다.
 * - 따라서, 실제 사용 데이터가 ASCII(영문/숫자/기호) 범위일 때 가장 안전합니다.
 * - 비-ASCII(한글 등)가 섞일 수 있다면 REGEX 대신 LINE_END / START_END를 권장합니다.
 *
 * 안전장치:
 * - 매칭이 장시간 안 되는 경우 버퍼가 무한히 커지는 것을 방지하기 위해 최대 버퍼 크기를 제한합니다.
 */
public class RegexFrameDecoder extends ByteToMessageDecoder {

    private static final int MAX_BUFFER_BYTES = 256 * 1024; // 256KB (필요시 설정화 가능)

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

            // 버퍼 폭주 방지(정규식이 매칭 안 되면 계속 쌓일 수 있음)
            if (readable > MAX_BUFFER_BYTES) {
                // 가장 앞부분을 잘라내고 최신 일부만 유지(재동기화 목적)
                int keep = Math.min(1024, readable); // 마지막 1KB만 유지
                in.readerIndex(writerIdx - keep);
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
                // 안전장치
                throw new IllegalStateException("regex match has invalid range");
            }

            // ASCII 기반(문자 index == 바이트 index) 전제
            // matchStart만큼 prefix 포함하여 소비하기 위해, 실제 slice는 readerIdx+matchStart에서 시작
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
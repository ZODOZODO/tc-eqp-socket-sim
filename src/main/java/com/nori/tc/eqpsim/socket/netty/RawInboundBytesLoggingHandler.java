package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * RawInboundBytesLoggingHandler
 *
 * 목적:
 * - framer(디코더) 이전 단계에서 "실제 수신 바이트"를 확인한다.
 * - 연결 초기 N번만 출력하여 로그 폭주를 방지한다.
 *
 * ✅ [M5 수정] slice retain 누락
 * - 기존: buf.slice() → 참조 카운트를 공유하므로,
 *         하위 핸들러가 buf를 release하면 slice가 무효화될 위험이 있다.
 * - 수정: buf.retainedSlice() → 독립적인 참조 카운트 획득,
 *         로깅 후 slice.release()로 명시적 해제.
 *
 * 주의:
 * - fireChannelRead(msg)는 finally 블록에서 수행하여 원본 buf가 항상 전달된다.
 * - slice는 로깅에만 사용하고 즉시 release한다.
 */
public class RawInboundBytesLoggingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RawInboundBytesLoggingHandler.class);

    /** 연결당 로그 출력 횟수 제한 */
    private final int maxLogsPerConn;

    /** 현재 연결에서 출력한 로그 횟수 */
    private int logged = 0;

    public RawInboundBytesLoggingHandler() {
        this(5);
    }

    public RawInboundBytesLoggingHandler(int maxLogsPerConn) {
        this.maxLogsPerConn = Math.max(1, maxLogsPerConn);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 로깅 처리와 무관하게 원본 msg는 항상 다음 핸들러로 전달한다.
        try {
            if (logged < maxLogsPerConn && msg instanceof ByteBuf buf) {
                logged++;

                int len = buf.readableBytes();
                int previewLen = Math.min(len, 256);

                // [M5 수정] retainedSlice() 사용: 독립적인 refcount 획득
                // - 하위 핸들러의 release와 무관하게 안전하게 사용 가능
                ByteBuf slice = buf.retainedSlice(buf.readerIndex(), previewLen);
                try {
                    String text = slice.toString(StandardCharsets.UTF_8);
                    String hex = ByteBufUtil.hexDump(slice);

                    log.info(StructuredLog.event("netty_rx_raw",
                            "connId", ctx.channel().id().asShortText(),
                            "remote", String.valueOf(ctx.channel().remoteAddress()),
                            "local", String.valueOf(ctx.channel().localAddress()),
                            "bytes", len,
                            "previewBytes", previewLen,
                            "text", text,
                            "hex", hex));
                } finally {
                    // retainedSlice는 refcount가 독립적이므로 반드시 명시적으로 release한다.
                    slice.release();
                }
            }
        } finally {
            // 원본 buf는 항상 다음 핸들러로 전달 (release 책임도 함께 위임)
            ctx.fireChannelRead(msg);
        }
    }
}
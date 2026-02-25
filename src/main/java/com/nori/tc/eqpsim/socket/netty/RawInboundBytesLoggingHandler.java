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
 *
 * 정책:
 * - 로그 폭주 방지를 위해 연결당 처음 N번만 출력한다.
 * - payload는 UTF-8로 한번 보여주되, 깨질 수 있으니 hex도 같이 출력한다.
 */
public class RawInboundBytesLoggingHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(RawInboundBytesLoggingHandler.class);

    /**
     * 연결당 로그 출력 횟수 제한(필요 시 조정)
     */
    private final int maxLogsPerConn;

    private int logged = 0;

    public RawInboundBytesLoggingHandler() {
        this(5);
    }

    public RawInboundBytesLoggingHandler(int maxLogsPerConn) {
        this.maxLogsPerConn = Math.max(1, maxLogsPerConn);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof ByteBuf buf) {
                if (logged < maxLogsPerConn) {
                    logged++;

                    int len = buf.readableBytes();
                    int previewLen = Math.min(len, 256);

                    // readerIndex를 변경하지 않고 미리보기
                    ByteBuf slice = buf.slice(buf.readerIndex(), previewLen);

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
                }
            }
        } finally {
            // 반드시 다음 핸들러로 전달
            ctx.fireChannelRead(msg);
        }
    }
}
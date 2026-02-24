package com.nori.tc.eqpsim.socket.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConnectionLimitHandler
 *
 * 목적:
 * - listen endpoint의 maxConn을 초과하는 접속은 즉시 close 한다.
 * - 정책(사용자 결정 21-B):
 *   - OS 레벨 accept 자체를 막을 수는 없으므로 "accept 후 close"로 제한한다.
 *
 * 동작:
 * - channelActive에서 카운터 증가
 * - maxConn 초과 시 즉시 감소 후 close, 이후 이벤트 전파하지 않음
 * - channelInactive에서 정상 연결에 대해 감소
 */
public class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private final int maxConn;
    private final AtomicInteger current;
    private boolean counted = false;

    public ConnectionLimitHandler(int maxConn, AtomicInteger current) {
        this.maxConn = maxConn;
        this.current = current;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        int now = current.incrementAndGet();
        if (now > maxConn) {
            current.decrementAndGet();
            ctx.close();
            return; // 다음 핸들러로 channelActive 이벤트 전달하지 않음
        }
        counted = true;
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        try {
            if (counted) {
                current.decrementAndGet();
            }
        } finally {
            ctx.fireChannelInactive();
        }
    }
}
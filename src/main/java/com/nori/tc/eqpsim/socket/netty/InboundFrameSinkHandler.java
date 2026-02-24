package com.nori.tc.eqpsim.socket.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * InboundFrameSinkHandler
 *
 * 목적:
 * - 현재 단계(5번)에서는 수신 프레임을 "소비"만 하여 메모리 누수를 방지한다.
 * - 핸드셰이크/시나리오 처리는 6번 이후에 이 자리에 대체/확장된다.
 *
 * 주의:
 * - SimpleChannelInboundHandler는 autoRelease=true일 때 ByteBuf를 자동 release한다.
 */
public class InboundFrameSinkHandler extends SimpleChannelInboundHandler<ByteBuf> {

    public InboundFrameSinkHandler() {
        super(true);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        // no-op (autoRelease=true로 자동 해제)
        // 다음 단계에서 이 핸들러를 "핸드셰이크/시나리오 엔진"으로 교체할 예정이다.
    }
}
package com.nori.tc.eqpsim.socket.framing;

import com.nori.tc.eqpsim.socket.config.SocketTypeProperties;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * LINE_END 프레이머 테스트
 */
class LineEndingFrameDecoderTests {

    @Test
    void lf_two_frames_in_one_buffer() {
        EmbeddedChannel ch = new EmbeddedChannel(new LineEndingFrameDecoder(SocketTypeProperties.LineEnding.LF));

        ch.writeInbound(Unpooled.copiedBuffer("A\nB\n", StandardCharsets.UTF_8));

        Object m1 = ch.readInbound();
        Object m2 = ch.readInbound();
        Object m3 = ch.readInbound();

        try {
            assertEquals("A", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
            assertEquals("B", ((io.netty.buffer.ByteBuf) m2).toString(StandardCharsets.UTF_8));
            assertNull(m3);
        } finally {
            ReferenceCountUtil.release(m1);
            ReferenceCountUtil.release(m2);
        }
        ch.finishAndReleaseAll();
    }

    @Test
    void crlf_split_across_buffers() {
        EmbeddedChannel ch = new EmbeddedChannel(new LineEndingFrameDecoder(SocketTypeProperties.LineEnding.CRLF));

        // "HELLO\r" 까지만 먼저 들어옴
        ch.writeInbound(Unpooled.copiedBuffer("HELLO\r", StandardCharsets.UTF_8));
        assertNull(ch.readInbound());

        // "\n"이 들어오면서 프레임 완성
        ch.writeInbound(Unpooled.copiedBuffer("\n", StandardCharsets.UTF_8));

        Object m1 = ch.readInbound();
        try {
            assertEquals("HELLO", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(m1);
        }
        ch.finishAndReleaseAll();
    }
}
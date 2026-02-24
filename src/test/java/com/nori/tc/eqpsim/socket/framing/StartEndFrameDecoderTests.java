package com.nori.tc.eqpsim.socket.framing;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * START_END 프레이머 테스트
 */
class StartEndFrameDecoderTests {

    @Test
    void stx_etx_extracts_payload_only() {
        byte[] stx = new byte[]{0x02};
        byte[] etx = new byte[]{0x03};

        EmbeddedChannel ch = new EmbeddedChannel(new StartEndFrameDecoder(stx, etx));

        // 쓰레기 + STX + payload + ETX + STX + payload + ETX
        byte[] bytes = new byte[]{
                'x', 'x',
                0x02, 'H', 'I', 0x03,
                0x02, 'O', 'K', 0x03
        };
        ch.writeInbound(Unpooled.wrappedBuffer(bytes));

        Object m1 = ch.readInbound();
        Object m2 = ch.readInbound();

        try {
            assertEquals("HI", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
            assertEquals("OK", ((io.netty.buffer.ByteBuf) m2).toString(StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(m1);
            ReferenceCountUtil.release(m2);
        }
        ch.finishAndReleaseAll();
    }

    @Test
    void waits_for_end_if_incomplete() {
        byte[] stx = new byte[]{0x02};
        byte[] etx = new byte[]{0x03};

        EmbeddedChannel ch = new EmbeddedChannel(new StartEndFrameDecoder(stx, etx));

        // STX + "HEL"까지만
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x02, 'H', 'E', 'L'}));
        assertNull(ch.readInbound());

        // 나머지 "LO" + ETX 들어오면 프레임 완성
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{'L', 'O', 0x03}));

        Object m1 = ch.readInbound();
        try {
            assertEquals("HELLO", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(m1);
        }
        ch.finishAndReleaseAll();
    }
}
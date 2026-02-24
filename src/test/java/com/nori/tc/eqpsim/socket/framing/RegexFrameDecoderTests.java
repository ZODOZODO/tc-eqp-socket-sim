package com.nori.tc.eqpsim.socket.framing;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REGEX 프레이머 테스트
 *
 * 테스트 패턴:
 * - "\\{[^}]+\\}" : 중괄호로 감싼 1개 프레임을 매칭
 * - 입력 "{A}{B}" -> 프레임 "{A}", "{B}"
 */
class RegexFrameDecoderTests {

    @Test
    void extracts_two_braced_frames() {
        Pattern p = Pattern.compile("\\{[^}]+\\}");
        EmbeddedChannel ch = new EmbeddedChannel(new RegexFrameDecoder(p, StandardCharsets.UTF_8));

        ch.writeInbound(Unpooled.copiedBuffer("{A}{B}", StandardCharsets.UTF_8));

        Object m1 = ch.readInbound();
        Object m2 = ch.readInbound();

        try {
            assertEquals("{A}", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
            assertEquals("{B}", ((io.netty.buffer.ByteBuf) m2).toString(StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(m1);
            ReferenceCountUtil.release(m2);
        }
        ch.finishAndReleaseAll();
    }

    @Test
    void waits_for_completion_across_buffers() {
        Pattern p = Pattern.compile("\\{[^}]+\\}");
        EmbeddedChannel ch = new EmbeddedChannel(new RegexFrameDecoder(p, StandardCharsets.UTF_8));

        ch.writeInbound(Unpooled.copiedBuffer("{A", StandardCharsets.UTF_8));
        assertNull(ch.readInbound());

        ch.writeInbound(Unpooled.copiedBuffer("}", StandardCharsets.UTF_8));

        Object m1 = ch.readInbound();
        try {
            assertEquals("{A}", ((io.netty.buffer.ByteBuf) m1).toString(StandardCharsets.UTF_8));
        } finally {
            ReferenceCountUtil.release(m1);
        }
        ch.finishAndReleaseAll();
    }
}
package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.HexByteSequenceParser;
import com.nori.tc.eqpsim.socket.framing.SocketFrameEncoderFactory;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.FaultStep;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * OutboundFrameSender (전역 송신 훅)
 *
 * 목적:
 * - payload(String)을 socketType 규칙으로 프레이밍하여 실제 송신한다.
 * - 채널 Attribute(ChannelAttributes.FAULT_STATE)에 저장된 FaultState를 적용하여
 *   delay/drop/fragment/corrupt 등의 장애를 "일관되게" 주입한다.
 *
 * 적용 범위:
 * - Handshake(INITIALIZE_REP) 송신
 * - Scenario(SEND/EMIT) 송신
 * - 향후 추가될 다른 송신 경로
 *
 * 주의:
 * - disconnect는 송신 훅이 아니라 "채널 close" 이벤트이므로 ScenarioRunner에서 처리한다.
 */
public final class OutboundFrameSender {

    private OutboundFrameSender() {
        // utility class
    }

    /**
     * payloadUtf8를 프레이밍 + 장애주입 적용 후 송신한다.
     *
     * @param ctx ChannelHandlerContext
     * @param eqp EqpRuntime(프레이밍/보호 길이 계산에 사용)
     * @param payloadUtf8 프레임 payload(UTF-8 문자열)
     */
    public static void send(ChannelHandlerContext ctx, EqpRuntime eqp, String payloadUtf8) {
        if (ctx == null || eqp == null || payloadUtf8 == null) {
            return;
        }
        if (!ctx.channel().isActive()) {
            return;
        }

        // 1) socketType 기반 프레이밍 적용 (ByteBuf)
        ByteBuf encoded = SocketFrameEncoderFactory.encodeUtf8(eqp.getSocketType(), ctx.alloc(), payloadUtf8);

        // 2) ByteBuf -> byte[] 로 변환 (fault 적용/분절 전송을 위해)
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.getBytes(encoded.readerIndex(), bytes);
        encoded.release();

        // 3) fault 상태 조회 (없으면 정상 송신)
        FaultState fs = ctx.channel().attr(ChannelAttributes.FAULT_STATE).get();
        if (fs == null) {
            writeBytes(ctx, bytes);
            return;
        }

        // 4) drop
        FaultState.Drop drop = fs.getDrop();
        if (FaultState.isActive(drop)) {
            boolean consume = (drop.mode == FaultStep.ScopeMode.NEXT) ? drop.next.tryConsumeOne() : true;
            if (consume && ThreadLocalRandom.current().nextDouble() < drop.rate) {
                return; // drop
            }
        }

        // 5) corrupt
        FaultState.Corrupt corrupt = fs.getCorrupt();
        if (FaultState.isActive(corrupt)) {
            boolean consume = (corrupt.mode == FaultStep.ScopeMode.NEXT) ? corrupt.next.tryConsumeOne() : true;
            if (consume && ThreadLocalRandom.current().nextDouble() < corrupt.rate) {
                corruptBytes(bytes, eqp, corrupt.protectFraming);
            }
        }

        // 6) delay
        FaultState.Delay delay = fs.getDelay();
        if (FaultState.isActive(delay)) {
            boolean consume = (delay.mode == FaultStep.ScopeMode.NEXT) ? delay.next.tryConsumeOne() : true;
            if (consume) {
                long d = delay.delayMs;
                if (delay.jitterMs > 0) {
                    d += ThreadLocalRandom.current().nextLong(0, delay.jitterMs + 1);
                }
                final byte[] sendBytes = bytes;
                ctx.executor().schedule(() -> sendNow(ctx, eqp, fs, sendBytes), d, TimeUnit.MILLISECONDS);
                return;
            }
        }

        // 7) 즉시 전송
        sendNow(ctx, eqp, fs, bytes);
    }

    private static void sendNow(ChannelHandlerContext ctx, EqpRuntime eqp, FaultState fs, byte[] bytes) {
        if (!ctx.channel().isActive()) {
            return;
        }

        // fragment
        FaultState.Fragment frag = fs.getFragment();
        if (FaultState.isActive(frag)) {
            boolean consume = (frag.mode == FaultStep.ScopeMode.NEXT) ? frag.next.tryConsumeOne() : true;
            if (consume) {
                int parts = ThreadLocalRandom.current().nextInt(frag.minParts, frag.maxParts + 1);
                List<byte[]> chunks = splitBytes(bytes, parts);

                for (byte[] c : chunks) {
                    ByteBuf buf = ctx.alloc().buffer(c.length);
                    buf.writeBytes(c);
                    ctx.write(buf);
                }
                ctx.flush();
                return;
            }
        }

        writeBytes(ctx, bytes);
    }

    private static void writeBytes(ChannelHandlerContext ctx, byte[] bytes) {
        if (!ctx.channel().isActive()) {
            return;
        }
        ByteBuf buf = ctx.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);
        ctx.writeAndFlush(buf);
    }

    /**
     * fragment 분절: framing 바이트까지 포함하여 랜덤하게 조각낸다(결정 18).
     */
    private static List<byte[]> splitBytes(byte[] bytes, int parts) {
        int len = bytes.length;
        if (parts <= 1 || len <= 1) return List.of(bytes);

        parts = Math.min(parts, len); // 각 파트 최소 1바이트
        int base = len / parts;
        int rem = len % parts;

        List<Integer> sizes = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            sizes.add(base + (i < rem ? 1 : 0));
        }

        // 간단 랜덤성: 인접 swap
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < sizes.size() - 1; i++) {
            if (rnd.nextBoolean()) {
                int a = sizes.get(i);
                int b = sizes.get(i + 1);
                sizes.set(i, b);
                sizes.set(i + 1, a);
            }
        }

        List<byte[]> out = new ArrayList<>(parts);
        int pos = 0;
        for (int sz : sizes) {
            out.add(Arrays.copyOfRange(bytes, pos, pos + sz));
            pos += sz;
        }
        return out;
    }

    /**
     * corrupt: protectFraming=true이면 prefix/suffix 영역은 제외하고 중간만 변조(결정 19 권장안).
     */
    private static void corruptBytes(byte[] bytes, EqpRuntime eqp, boolean protectFraming) {
        int from = 0;
        int to = bytes.length;

        if (protectFraming) {
            int[] ps = computePrefixSuffix(eqp);
            int prefix = ps[0];
            int suffix = ps[1];

            from = Math.min(bytes.length, prefix);
            to = Math.max(from, bytes.length - suffix);
        }

        if (to - from <= 0) return;

        int idx = ThreadLocalRandom.current().nextInt(from, to);
        bytes[idx] = (byte) (bytes[idx] ^ 0x5A);
    }

    private static int[] computePrefixSuffix(EqpRuntime eqp) {
        var st = eqp.getSocketType();
        return switch (st.getKind()) {
            case REGEX -> new int[]{0, 0};
            case LINE_END -> {
                int suffix = switch (st.getLineEnding()) {
                    case LF, CR -> 1;
                    case CRLF -> 2;
                };
                yield new int[]{0, suffix};
            }
            case START_END -> {
                int prefix = HexByteSequenceParser.parseHexSequence(st.getStartHex()).length;
                int suffix = HexByteSequenceParser.parseHexSequence(st.getEndHex()).length;
                yield new int[]{prefix, suffix};
            }
        };
    }
}
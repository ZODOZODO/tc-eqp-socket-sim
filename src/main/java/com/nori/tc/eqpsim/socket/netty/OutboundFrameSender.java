package com.nori.tc.eqpsim.socket.netty;

import com.nori.tc.eqpsim.socket.framing.HexByteSequenceParser;
import com.nori.tc.eqpsim.socket.framing.SocketFrameEncoderFactory;
import com.nori.tc.eqpsim.socket.logging.StructuredLog;
import com.nori.tc.eqpsim.socket.runtime.EqpRuntime;
import com.nori.tc.eqpsim.socket.scenario.FaultStep;
import com.nori.tc.eqpsim.socket.scenario.runtime.FaultState;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * OutboundFrameSender
 *
 * 역할:
 * - payload(String)를 socketType 규칙으로 프레이밍하여 실제 송신한다.
 * - FaultState에 저장된 장애 상태를 적용한다.
 *
 * ✅ [S3 수정] fault 적용 순서 수정
 *
 * 기존(잘못된) 순서: drop → corrupt → delay → sendNow(fragment)
 * - 문제: delay가 적용된 메시지는 drop 판정을 이미 통과한 상태
 *         즉, delay된 메시지는 절대 drop되지 않음 (의미 없는 delay+drop 조합)
 *
 * 수정(올바른) 순서: delay → drop → corrupt → sendNow(fragment)
 * - delay: 먼저 지연을 결정하고, 지연 후 실제 송신 직전에 drop/corrupt를 판정
 *   → delay된 메시지도 drop될 수 있음 (올바른 fault 조합 동작)
 * - drop: 실제 송신 직전 확률적 누락
 * - corrupt: 송신 직전 바이트 변조
 * - fragment: sendNow에서 물리적 전송 직전에 분절 (framing 보호 가능)
 *
 * 최종 실행 흐름:
 *   send() → 프레이밍 → delay 판정
 *             ├─ delay 있음 → 스케줄 → [delay 후] → sendNow()
 *             └─ delay 없음 → sendNow()
 *
 *   sendNow() → drop 판정
 *                ├─ drop → return (폐기)
 *                └─ corrupt 판정 → fragment 판정 → writeBytes()
 */
public final class OutboundFrameSender {

    private static final Logger log = LoggerFactory.getLogger(OutboundFrameSender.class);

    private OutboundFrameSender() {
        // utility class
    }

    /**
     * payloadUtf8를 프레이밍 + 장애주입 적용 후 송신한다.
     *
     * @param ctx         ChannelHandlerContext
     * @param eqp         EqpRuntime (프레이밍/보호 길이 계산에 사용)
     * @param payloadUtf8 프레임 payload (UTF-8 문자열)
     */
    public static void send(ChannelHandlerContext ctx, EqpRuntime eqp, String payloadUtf8) {
        if (ctx == null || eqp == null || payloadUtf8 == null) return;
        if (!ctx.channel().isActive()) return;

        // 1) socketType 기반 프레이밍 적용
        ByteBuf encoded = SocketFrameEncoderFactory.encodeUtf8(eqp.getSocketType(), ctx.alloc(), payloadUtf8);

        // 2) ByteBuf → byte[] 변환 (fault 적용/분절 전송을 위해)
        byte[] bytes = new byte[encoded.readableBytes()];
        encoded.getBytes(encoded.readerIndex(), bytes);
        encoded.release();

        // 3) fault 상태 조회 (없으면 정상 송신)
        FaultState fs = ctx.channel().attr(ChannelAttributes.FAULT_STATE).get();
        if (fs == null) {
            writeBytes(ctx, bytes);
            return;
        }

        // ✅ [S3 수정] fault 적용 순서: delay → (이후 sendNow에서 drop → corrupt → fragment)
        //
        // delay를 먼저 처리해야 delay된 메시지에도 drop/corrupt가 적용된다.
        // 기존 코드는 drop → corrupt → delay 순서였으므로
        // delay 경로를 탄 메시지는 이미 drop/corrupt 판정을 통과한 상태가 됨 → 버그
        FaultState.Delay delay = fs.getDelay();
        if (FaultState.isActive(delay)) {
            boolean consume = (delay.mode == FaultStep.ScopeMode.NEXT) ? delay.next.tryConsumeOne() : true;
            if (consume) {
                long d = delay.delayMs;
                if (delay.jitterMs > 0) {
                    d += ThreadLocalRandom.current().nextLong(0, delay.jitterMs + 1);
                }
                final byte[] sendBytes = bytes;
                // delay 후 sendNow: sendNow 내부에서 drop/corrupt/fragment 순서로 처리
                ctx.executor().schedule(() -> sendNow(ctx, eqp, fs, sendBytes), d, TimeUnit.MILLISECONDS);
                return;
            }
        }

        // delay 없음 → 즉시 sendNow
        sendNow(ctx, eqp, fs, bytes);
    }

    /**
     * 실제 송신 직전 처리: drop → corrupt → fragment → writeBytes
     *
     * delay가 없거나 delay 후 스케줄된 경우 모두 이 메서드를 통과한다.
     * → delay된 메시지도 drop/corrupt 판정을 받는다. ✅
     */
    private static void sendNow(ChannelHandlerContext ctx, EqpRuntime eqp, FaultState fs, byte[] bytes) {
        if (!ctx.channel().isActive()) return;

        // ✅ [S3 수정] drop: sendNow에서 판정 (delay 후에도 drop 적용)
        FaultState.Drop drop = fs.getDrop();
        if (FaultState.isActive(drop)) {
            boolean consume = (drop.mode == FaultStep.ScopeMode.NEXT) ? drop.next.tryConsumeOne() : true;
            if (consume && ThreadLocalRandom.current().nextDouble() < drop.rate) {
                log.debug(StructuredLog.event("fault_drop",
                        "connId", ctx.channel().id().asShortText(),
                        "rate", drop.rate));
                return; // 폐기
            }
        }

        // ✅ [S3 수정] corrupt: drop 통과 후 변조 (delay 후에도 corrupt 적용)
        FaultState.Corrupt corrupt = fs.getCorrupt();
        if (FaultState.isActive(corrupt)) {
            boolean consume = (corrupt.mode == FaultStep.ScopeMode.NEXT) ? corrupt.next.tryConsumeOne() : true;
            if (consume && ThreadLocalRandom.current().nextDouble() < corrupt.rate) {
                corruptBytes(bytes, eqp, corrupt.protectFraming);
            }
        }

        // fragment: 물리적 전송 직전 분절
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
        if (!ctx.channel().isActive()) return;
        ByteBuf buf = ctx.alloc().buffer(bytes.length);
        buf.writeBytes(bytes);
        ctx.writeAndFlush(buf);
    }

    /**
     * 바이트 배열을 parts개 조각으로 랜덤 분절한다.
     * - framing 바이트를 포함하여 분절 (결정 18)
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
     * corrupt: protectFraming=true이면 prefix/suffix 영역을 제외하고 중간만 변조.
     */
    private static void corruptBytes(byte[] bytes, EqpRuntime eqp, boolean protectFraming) {
        int from = 0;
        int to = bytes.length;

        if (protectFraming) {
            int[] ps = computePrefixSuffix(eqp);
            from = Math.min(bytes.length, ps[0]);
            to = Math.max(from, bytes.length - ps[1]);
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
package com.nori.tc.eqpsim.socket.framing;

import io.netty.buffer.ByteBuf;

/**
 * ByteBuf에서 byte[] 시퀀스를 찾는 유틸.
 *
 * Netty의 기본 indexOf는 단일 byte 검색에 최적화되어 있어,
 * 멀티 바이트 시퀀스(start/end, CRLF 등)는 별도 구현합니다.
 *
 * 성능:
 * - O(n*m) 단순 검색입니다.
 * - 현재 목표(실행 120대 수준)에서는 충분합니다.
 */
public final class ByteBufSearch {

    private ByteBufSearch() {
        // utility class
    }

    /**
     * buf 내에서 sequence가 처음 등장하는 index(absolute index)를 반환합니다.
     *
     * @param buf ByteBuf
     * @param fromInclusive 검색 시작 index (absolute)
     * @param toExclusive 검색 끝 index (absolute, exclusive)
     * @param sequence 검색할 시퀀스(길이 1 이상)
     * @return 발견 시 absolute index, 없으면 -1
     */
    public static int indexOf(ByteBuf buf, int fromInclusive, int toExclusive, byte[] sequence) {
        if (sequence == null || sequence.length == 0) {
            throw new IllegalArgumentException("sequence must not be empty");
        }
        if (fromInclusive < 0 || toExclusive < 0 || fromInclusive > toExclusive) {
            throw new IllegalArgumentException("invalid range");
        }

        int seqLen = sequence.length;
        int maxStart = toExclusive - seqLen;
        for (int i = fromInclusive; i <= maxStart; i++) {
            boolean match = true;
            for (int j = 0; j < seqLen; j++) {
                if (buf.getByte(i + j) != sequence[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }
}
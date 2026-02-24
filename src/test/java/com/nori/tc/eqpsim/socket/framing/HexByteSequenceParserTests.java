package com.nori.tc.eqpsim.socket.framing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * HexByteSequenceParser 테스트
 */
class HexByteSequenceParserTests {

    @Test
    void parses_single_byte() {
        assertArrayEquals(new byte[]{0x02}, HexByteSequenceParser.parseHexSequence("02"));
        assertArrayEquals(new byte[]{0x02}, HexByteSequenceParser.parseHexSequence("0x02"));
    }

    @Test
    void parses_multi_bytes_with_spaces_or_commas() {
        assertArrayEquals(new byte[]{0x02, 0x03}, HexByteSequenceParser.parseHexSequence("02 03"));
        assertArrayEquals(new byte[]{0x02, 0x03}, HexByteSequenceParser.parseHexSequence("0x02,0x03"));
    }

    @Test
    void parses_compact_even_length_string() {
        assertArrayEquals(new byte[]{0x02, 0x03}, HexByteSequenceParser.parseHexSequence("0203"));
    }
}
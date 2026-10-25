package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The growable buffer and the streamed (non-canonical) map path; byte equality with {@link RefMsgpack}. */
class MsgpackBufferTest {

    @Test
    void bigValuesGrowTheBufferAndStayIdentical() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 70_000; i++) { // map32 header
            m.put("k" + i, (long) i * 1_000_003L);
        }
        m.put("bin", new byte[300_000]);
        m.put("str", "é".repeat(100_000));
        List<Object> arr = new ArrayList<>();
        for (int i = 0; i < 70_000; i++) { // array32 header
            arr.add(i % 2 == 0 ? (Object) (i * -3L) : "x");
        }
        m.put("arr", arr);
        assertArrayEquals(RefMsgpack.encode(m, false), Msgpack.encode(m, false));
        assertArrayEquals(RefMsgpack.encode(m, true), Msgpack.encode(m, true));
    }

    @Test
    void streamedMapsKeepOrderAndNullsAndCanonicalMapsDropThem() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", null);
        m.put("a", 1L);
        assertArrayEquals(WireTest.hex("82 a17a c0 a161 01"), Msgpack.encode(m, false));
        assertArrayEquals(WireTest.hex("81 a161 01"), Msgpack.encode(m, true));
    }

    @Test
    void nonStringKeysAreRejectedInBothModes() {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> Msgpack.encode(m, false));
        assertThrows(IllegalArgumentException.class, () -> Msgpack.encode(m, true));
        assertThrows(IllegalArgumentException.class, () -> Msgpack.encode(new Object(), false));
    }

    @Test
    void framedEncodingReservesThePrefixOnly() {
        for (Object v : new Object[] {null, 5L, "text", List.of(1L, 2L), Map.of("a", 1L)}) {
            byte[] plain = Msgpack.encode(v, false);
            byte[] framed = Msgpack.encodeFramed(v, false);
            assertEquals(plain.length + Framing.PREFIX, framed.length);
            byte[] tail = new byte[plain.length];
            System.arraycopy(framed, Framing.PREFIX, tail, 0, tail.length);
            assertArrayEquals(plain, tail);
        }
    }

    @Test
    void randomValuesFramedEqualPlain() {
        Random r = new Random(11);
        for (int i = 0; i < 100; i++) {
            Object v = EncoderBenchTest.random(r, 0);
            byte[] framed = Msgpack.encodeFramed(v, true);
            assertArrayEquals(RefMsgpack.encode(v, true),
                    java.util.Arrays.copyOfRange(framed, Framing.PREFIX, framed.length));
            assertTrue(framed[0] == 0 && framed[1] == 0 && framed[2] == 0 && framed[3] == 0);
        }
    }
}

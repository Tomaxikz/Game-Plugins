package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireTest {

    static byte[] hex(String s) {
        return HexFormat.of().parseHex(s.replace(" ", ""));
    }

    @Test
    void smallestIntEncodings() {
        assertArrayEquals(hex("00"), Msgpack.encode(0L, false));
        assertArrayEquals(hex("7f"), Msgpack.encode(127L, false));
        assertArrayEquals(hex("cc80"), Msgpack.encode(128L, false));
        assertArrayEquals(hex("cd0100"), Msgpack.encode(256L, false));
        assertArrayEquals(hex("ce00010000"), Msgpack.encode(65536L, false));
        assertArrayEquals(hex("e0"), Msgpack.encode(-32L, false));
        assertArrayEquals(hex("d080"), Msgpack.encode(-128L, false));
        assertArrayEquals(hex("d0df"), Msgpack.encode(-33L, false));
    }

    @Test
    void roundTripNestedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("n", -5L);
        m.put("f", 1.5);
        m.put("list", List.of("a", 2L));
        m.put("bin", new byte[] {1, 2, 3});
        Object back = Msgpack.decode(Msgpack.encode(m, false));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) back).get("ok"));
        assertEquals(-5L, ((Map<?, ?>) back).get("n"));
        assertEquals(1.5, ((Map<?, ?>) back).get("f"));
        assertEquals(List.of("a", 2L), ((Map<?, ?>) back).get("list"));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) ((Map<?, ?>) back).get("bin"));
    }

    @Test
    void canonicalSortsKeysByUtf8BytesAndDropsNulls() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1L);
        m.put("a", null);
        m.put("aa", 2L);
        m.put("\u00e9", 3L);
        // keys in UTF-8 byte order: "aa" (61 61) < "b" (62) < "\u00e9" (c3 a9); "a" is dropped as null
        assertArrayEquals(hex("83" + "a26161" + "02" + "a162" + "01" + "a2c3a9" + "03"),
                Msgpack.encode(m, true));
    }

    @Test
    void integralDoublesBecomeIntsOnlyInCanonicalMode() {
        assertArrayEquals(hex("01"), Msgpack.encode(1.0, true));
        assertEquals(9, Msgpack.encode(1.0, false).length);
        assertEquals(9, Msgpack.encode(1.5, true).length);
    }

    @Test
    void decodeRejectsTrailingAndTruncatedInput() {
        assertThrows(IllegalArgumentException.class, () -> Msgpack.decode(hex("0102")));
        assertThrows(IllegalArgumentException.class, () -> Msgpack.decode(hex("a5 61")));
        assertNull(Msgpack.decode(hex("c0")));
    }

    @Test
    void framingRoundTripAndLimits() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Framing.write(out, hex("0102ff"));
        assertArrayEquals(hex("00000003 0102ff"), out.toByteArray());
        byte[] body = Framing.read(new ByteArrayInputStream(out.toByteArray()));
        assertArrayEquals(hex("0102ff"), body);
        assertNull(Framing.read(new ByteArrayInputStream(new byte[0])));

        byte[] tooBig = hex("00400001");
        assertThrows(Framing.FrameTooLargeException.class,
                () -> Framing.read(new ByteArrayInputStream(tooBig)));
        byte[] truncated = hex("00000005 0102");
        assertThrows(java.io.IOException.class, () -> Framing.read(new ByteArrayInputStream(truncated)));
    }

    @Test
    void jsonRoundTrip() {
        Object v = Json.parse("{\"a\":[1,2.5,\"x\\n\"],\"b\":null,\"c\":true}");
        assertEquals("{\"a\":[1,2.5,\"x\\n\"],\"b\":null,\"c\":true}", Json.write(v));
        assertTrue(v instanceof Map);
    }
}

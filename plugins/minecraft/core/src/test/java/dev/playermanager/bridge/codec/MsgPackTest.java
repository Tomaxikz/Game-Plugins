package dev.playermanager.bridge.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MsgPackTest {
    static byte[] hex(String s) {
        String[] p = s.trim().split("\\s+");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    @Test void roundTripAllTypes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", null);
        m.put("t", true);
        m.put("i", 1234567890123L);
        m.put("neg", -5L);
        m.put("neg2", -200000L);
        m.put("f", 1.5);
        m.put("s", "héllo");
        m.put("bin", new byte[] {1, 2, 3});
        m.put("arr", Arrays.asList(1, "x", false));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("k", 7);
        m.put("map", inner);
        Map<String, Object> d = MsgPack.decodeMap(MsgPack.encode(m));
        assertNull(d.get("n"));
        assertEquals(true, d.get("t"));
        assertEquals(1234567890123L, d.get("i"));
        assertEquals(-5L, d.get("neg"));
        assertEquals(-200000L, d.get("neg2"));
        assertEquals(1.5, d.get("f"));
        assertEquals("héllo", d.get("s"));
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) d.get("bin"));
        assertEquals(Arrays.<Object>asList(1L, "x", false), d.get("arr"));
        assertEquals(7L, ((Map<?, ?>) d.get("map")).get("k"));
    }

    @Test void smallestIntegerEncodings() {
        assertArrayEquals(hex("00"), MsgPack.encode(0));
        assertArrayEquals(hex("7f"), MsgPack.encode(127));
        assertArrayEquals(hex("cc 80"), MsgPack.encode(128));
        assertArrayEquals(hex("cd 01 00"), MsgPack.encode(256));
        assertArrayEquals(hex("ce 00 01 00 00"), MsgPack.encode(65536));
        assertArrayEquals(hex("ff"), MsgPack.encode(-1));
        assertArrayEquals(hex("e0"), MsgPack.encode(-32));
        assertArrayEquals(hex("d0 df"), MsgPack.encode(-33));
        assertArrayEquals(hex("d1 ff 7f"), MsgPack.encode(-129));
    }

    @Test void stringHeaders() {
        assertEquals(1 + 31, MsgPack.encode(new String(new char[31]).replace('\0', 'a')).length);
        assertEquals(2 + 32, MsgPack.encode(new String(new char[32]).replace('\0', 'a')).length);
        assertEquals(3 + 256, MsgPack.encode(new String(new char[256]).replace('\0', 'a')).length);
    }

    @Test void canonicalSortsKeysDropsNullAndIntegralFloats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1.0);
        m.put("a", 2.5);
        m.put("z", null);
        // map(2) a:2.5(float64) b:1(int)
        assertArrayEquals(hex("82 a1 61 cb 40 04 00 00 00 00 00 00 a1 62 01"), MsgPack.encodeCanonical(m));
        // non-canonical keeps floats and nil
        assertTrue(MsgPack.encode(m).length > MsgPack.encodeCanonical(m).length);
    }

    @Test void canonicalSortsByUtf8Bytes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("é", 1); // 0xc3 0xa9
        m.put("z", 2);
        byte[] enc = MsgPack.encodeCanonical(m);
        Map<String, Object> d = MsgPack.decodeMap(enc);
        assertEquals(Arrays.asList("z", "é"), new ArrayList<>(d.keySet()));
    }

    @Test void rejectsTruncatedAndTrailing() {
        assertThrows(MsgPack.MsgPackException.class, () -> MsgPack.decode(hex("a5 61 62")));
        assertThrows(MsgPack.MsgPackException.class, () -> MsgPack.decode(hex("01 02")));
        assertThrows(MsgPack.MsgPackException.class, () -> MsgPack.decode(hex("dd ff ff ff ff")));
        assertThrows(MsgPack.MsgPackException.class, () -> MsgPack.decode(hex("81 01 02")));
    }

    @Test void depthLimit() {
        byte[] deep = new byte[200];
        Arrays.fill(deep, (byte) 0x91);
        deep[199] = 0;
        assertThrows(MsgPack.MsgPackException.class, () -> MsgPack.decode(deep));
    }

    // ---- hash vectors (decision D17: the doc's bytes were malformed; fixstr of 15 bytes is 0xaf)

    @Test void renderHashTestVectorCorrected() {
        Map<String, Object> subset = new LinkedHashMap<>();
        subset.put("id", "minecraft:stone");
        byte[] enc = MsgPack.encodeCanonical(subset);
        assertArrayEquals(hex("81 a2 69 64 af 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65"), enc);
        assertEquals("90d892ebcdb67d59", Hashes.renderHash(subset));
    }

    @Test void documentedErratumBytesHashAsDocumented() {
        // items-and-assets.md section 2 lists these bytes (0xae instead of 0xaf): they hash to the documented value.
        byte[] malformed = hex("81 a2 69 64 ae 6d 69 6e 65 63 72 61 66 74 3a 73 74 6f 6e 65");
        assertEquals("027f2bef638386bd", Hashes.sha256Hex(malformed).substring(0, 16));
    }

    @Test void fingerprintStripsSlotHashTruncatedRecursively() {
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("id", "minecraft:dirt");
        child.put("count", 2);
        Map<String, Object> childWithJunk = new LinkedHashMap<>(child);
        childWithJunk.put("slot", 5);
        childWithJunk.put("hash", "x");
        childWithJunk.put("fingerprint", "y");
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "minecraft:shulker_box");
        a.put("count", 1);
        a.put("contents", Arrays.asList(child));
        Map<String, Object> b = new LinkedHashMap<>(a);
        b.put("contents", Arrays.asList(childWithJunk));
        b.put("slot", 9);
        b.put("hash", "abc");
        b.put("truncated", true);
        assertEquals(Hashes.fingerprint(a), Hashes.fingerprint(b));
        assertEquals(64, Hashes.fingerprint(a).length());
    }

    @Test void manifestHashIsStableOverPairs() {
        List<String[]> kv = new ArrayList<>();
        kv.add(new String[] {"item:a:b", "0011223344556677"});
        String h1 = Hashes.manifestHash(kv);
        kv.add(new String[] {"item:a:c", "8899aabbccddeeff"});
        assertNotEquals(h1, Hashes.manifestHash(kv));
        assertEquals(16, h1.length());
    }

    @Test void jsonRoundTripAndLenient() {
        Map<String, Object> m = Json.parseObject("{\"a\":[1,2.5,\"x\\n\"],\"b\":{\"c\":null},\"d\":true}");
        assertEquals(Arrays.<Object>asList(1L, 2.5, "x\n"), m.get("a"));
        Object o = Json.parseLenient("// c\n{\"a\":1,/* x */ \"b\":[1,2,],}");
        assertEquals(2, ((Map<?, ?>) o).size());
        assertEquals("{\"k\":\"v\\\"\"}", Json.write(Json.parseObject("{\"k\":\"v\\\"\"}")));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":}"));
    }

    @Test void utf8() { assertEquals(3, "€".getBytes(StandardCharsets.UTF_8).length); }
}

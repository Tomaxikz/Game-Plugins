package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalTest {

    /** Correct canonical msgpack for {"id":"minecraft:stone"}: fixstr(15) is 0xaf, not 0xae. */
    static final String CORRECT_STONE = "81 a2 6964 af 6d696e6563726166743a73746f6e65";

    /** Bytes exactly as written in the brief / items-and-assets.md §2 (header 0xae for a 15-byte str). */
    static final String CONTRACT_STONE = "81 a2 6964 ae 6d696e6563726166743a73746f6e65";

    @Test
    void canonicalEncodingOfStoneSubset() {
        Map<String, Object> subset = Canonical.renderSubset(Map.of("id", "minecraft:stone"));
        assertArrayEquals(WireTest.hex(CORRECT_STONE), Msgpack.encode(subset, true));
    }

    @Test
    void hashOfStoneSubsetFromCorrectEncoding() {
        // SHA-256 over the correct encoding. The contract vector 027f2bef638386bd differs; see NOTES-section.md.
        Map<String, Object> subset = Canonical.renderSubset(Map.of("id", "minecraft:stone"));
        assertEquals("90d892ebcdb67d59", Canonical.hash(subset));
    }

    @Test
    void contractVectorDigestIsTheSha256OfTheContractBytes() {
        // Proves the contract's hash 027f2bef638386bd is SHA-256 over the 0xae bytes, not a correct msgpack.
        assertEquals("027f2bef638386bd", Canonical.sha256Hex(WireTest.hex(CONTRACT_STONE)).substring(0, 16));
    }

    @Test
    void contractVectorBytesAreNotValidMsgpack() {
        // 0xae promises 14 bytes but 15 follow: the decoder reports trailing bytes.
        assertThrows(IllegalArgumentException.class, () -> Msgpack.decode(WireTest.hex(CONTRACT_STONE)));
    }

    @Test
    void renderSubsetDropsNonRenderKeys() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "minecraft:stone");
        item.put("count", 64L);
        item.put("name", Map.of("plain", "x"));
        item.put("tint", List.of(0xff0000L));
        Map<String, Object> subset = Canonical.renderSubset(item);
        assertEquals(Map.of("id", "minecraft:stone", "tint", List.of(0xff0000L)), subset);
    }

    @Test
    void fingerprintIsFull64HexAndIgnoresSlotHashFingerprint() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "minecraft:diamond_sword");
        a.put("count", 1L);
        a.put("slot", 0L);
        Map<String, Object> b = new LinkedHashMap<>(a);
        b.put("slot", 7L);
        b.put("hash", "0000000000000000");
        b.put("fingerprint", "x");
        b.put("truncated", true);
        String fa = Canonical.fingerprint(a);
        assertEquals(64, fa.length());
        assertTrue(fa.matches("[0-9a-f]{64}"));
        assertEquals(fa, Canonical.fingerprint(b));
    }

    @Test
    void fingerprintChangesWhenCountChanges() {
        Map<String, Object> a = Map.of("id", "x:y", "count", 1L);
        Map<String, Object> b = Map.of("id", "x:y", "count", 2L);
        assertTrue(!Canonical.fingerprint(a).equals(Canonical.fingerprint(b)));
    }

    @Test
    void utf8OrderingIsUnsignedBytes() {
        assertTrue(Msgpack.compareUtf8("a".getBytes(StandardCharsets.UTF_8),
                "é".getBytes(StandardCharsets.UTF_8)) < 0);
    }
}

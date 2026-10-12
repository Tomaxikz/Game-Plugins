package dev.playermanager.bridge.nbt;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class NbtTest {
    static Map<String, Object> sample() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", (byte) 1);
        inner.put("s", (short) 2);
        inner.put("i", 3);
        inner.put("l", 4L);
        inner.put("f", 1.5f);
        inner.put("d", 2.5d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("str", "Hellö §c");
        m.put("inner", inner);
        m.put("list", new ArrayList<Object>(Arrays.asList(1, 2, 3)));
        m.put("empty", new ArrayList<Object>());
        m.put("ba", new byte[] {1, -2});
        m.put("ia", new int[] {7, 8});
        m.put("la", new long[] {9L});
        return m;
    }

    @Test void parsesAllTagTypes() throws Exception {
        Map<String, Object> r = Nbt.readRoot(NbtWriter.write(sample(), "root"));
        assertEquals("Hellö §c", r.get("str"));
        Map<?, ?> inner = (Map<?, ?>) r.get("inner");
        assertEquals((byte) 1, inner.get("b"));
        assertEquals((short) 2, inner.get("s"));
        assertEquals(3, inner.get("i"));
        assertEquals(4L, inner.get("l"));
        assertEquals(1.5f, inner.get("f"));
        assertEquals(2.5d, inner.get("d"));
        assertEquals(Arrays.asList(1, 2, 3), r.get("list"));
        assertEquals(0, ((List<?>) r.get("empty")).size());
        assertArrayEquals(new byte[] {1, -2}, (byte[]) r.get("ba"));
        assertArrayEquals(new int[] {7, 8}, (int[]) r.get("ia"));
        assertArrayEquals(new long[] {9L}, (long[]) r.get("la"));
    }

    @Test void gzipAndZlibAndPlain() throws Exception {
        byte[] plain = NbtWriter.write(sample(), "");
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        try (GZIPOutputStream o = new GZIPOutputStream(g)) { o.write(plain); }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream o = new DeflaterOutputStream(z)) { o.write(plain); }
        assertEquals("Hellö §c", Nbt.readRoot(g.toByteArray()).get("str"));
        assertEquals("Hellö §c", Nbt.readRoot(z.toByteArray()).get("str"));
        assertEquals("Hellö §c", Nbt.readRoot(plain).get("str"));
    }

    @Test void friendlyConversion() throws Exception {
        Object f = Nbt.toFriendly(Nbt.readRoot(NbtWriter.write(sample(), "")));
        Map<?, ?> m = (Map<?, ?>) f;
        Map<?, ?> inner = (Map<?, ?>) m.get("inner");
        assertEquals(1L, inner.get("b"));
        assertEquals(4L, inner.get("l"));
        assertEquals(1.5, inner.get("f"));
        assertEquals(Arrays.<Object>asList(1L, -2L), m.get("ba"));
        assertEquals(Arrays.<Object>asList(7L, 8L), m.get("ia"));
    }

    @Test void rejectsBadInput() {
        assertThrows(Exception.class, () -> Nbt.readRoot(new byte[] {9, 0, 0}));            // root not a compound
        assertThrows(Exception.class, () -> Nbt.readRoot(new byte[] {10, 0, 0, 7, 0, 1, 'a', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff}));  // oversize array
        assertThrows(Exception.class, () -> Nbt.readRoot(new byte[] {10, 0, 0, 1, 0, 1}));   // truncated
    }

    @Test void depthLimit() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> cur = m;
        for (int i = 0; i < Nbt.MAX_DEPTH + 10; i++) {
            Map<String, Object> n = new LinkedHashMap<>();
            cur.put("c", n);
            cur = n;
        }
        byte[] data = NbtWriter.write(m, "");
        assertThrows(Nbt.NbtException.class, () -> Nbt.readRoot(data));
    }

    @Test void snbt() {
        Map<String, Object> tag = new LinkedHashMap<>();
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("Name", "{\"text\":\"Rock\"}");
        tag.put("display", display);
        tag.put("Damage", 3);
        tag.put("flag", (byte) 1);
        tag.put("big", 5L);
        tag.put("arr", new int[] {1, 2});
        tag.put("l", new ArrayList<Object>(Arrays.asList(1.5d, 2.5d)));
        assertEquals("{display:{Name:\"{\\\"text\\\":\\\"Rock\\\"}\"},Damage:3,flag:1b,big:5L,arr:[I;1,2],l:[1.5d,2.5d]}", Snbt.write(tag));
        Map<String, Object> loose = new LinkedHashMap<>();
        loose.put("a", 5L);
        loose.put("b", 1L << 40);
        loose.put("c", true);
        loose.put("d", 0.5);
        loose.put("e", "x y");
        assertEquals("{a:5,b:1099511627776L,c:true,d:0.5d,e:\"x y\"}", Snbt.writeLoose(loose));
    }
}

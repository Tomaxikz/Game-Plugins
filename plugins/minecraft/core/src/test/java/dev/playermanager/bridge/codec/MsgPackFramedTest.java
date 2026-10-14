package dev.playermanager.bridge.codec;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.net.Framing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MsgPackFramedTest {
    private static String rep(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }

    /** Reference str encoder (the pre-optimisation behaviour). */
    private static byte[] refStr(String s) {
        byte[] a = s.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        if (a.length < 32) o.write(0xa0 | a.length);
        else if (a.length < 256) { o.write(0xd9); o.write(a.length); }
        else if (a.length < 65536) { o.write(0xda); o.write(a.length >> 8); o.write(a.length); }
        else { o.write(0xdb); o.write(a.length >>> 24); o.write(a.length >>> 16); o.write(a.length >>> 8); o.write(a.length); }
        o.write(a, 0, a.length);
        return o.toByteArray();
    }

    @Test void stringEncodingUnchangedForAsciiAndUnicode() {
        String[] cases = {"", "a", "minecraft:stone", rep('x', 31), rep('x', 32), rep('x', 255), rep('x', 256), rep('x', 65535), rep('x', 70000),
                "héllo", "€" + rep('y', 40), "😀 emoji", rep('é', 200), "plain" + rep('z', 300) + "ü"};
        for (String s : cases) assertArrayEquals(refStr(s), MsgPack.encode(s), "len " + s.length());
    }

    private static Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", 7L);
        m.put("ok", true);
        List<Object> l = new ArrayList<Object>();
        for (int i = 0; i < 500; i++) l.add("item-" + i);
        m.put("data", l);
        return m;
    }

    @Test void framedEqualsFrameOfEncode() {
        Map<String, Object> m = sample();
        assertArrayEquals(Framing.frame(MsgPack.encode(m)), MsgPack.encodeFramed(m));
        assertArrayEquals(Framing.frame(MsgPack.encode(m)), MsgPack.encodeFramed(m));   // buffer reuse on the same thread
        assertArrayEquals(Framing.frame(MsgPack.encode("x")), MsgPack.encodeFramed("x"));          // a shorter value after a longer one
    }

    @Test void preEncodedIsByteIdenticalToTheInlineValue() {
        Map<String, Object> data = sample();
        Map<String, Object> inline = new LinkedHashMap<String, Object>();
        inline.put("id", 3L); inline.put("ok", true); inline.put("data", data);
        Map<String, Object> pre = new LinkedHashMap<String, Object>();
        pre.put("id", 3L); pre.put("ok", true); pre.put("data", new PreEncoded(data));
        assertArrayEquals(MsgPack.encode(inline), MsgPack.encode(pre));
        assertArrayEquals(MsgPack.encodeFramed(inline), MsgPack.encodeFramed(pre));
        PreEncoded shared = new PreEncoded(data);
        byte[] a = shared.bytes();
        assertSame(a, shared.bytes());                          // encoded once
    }
}

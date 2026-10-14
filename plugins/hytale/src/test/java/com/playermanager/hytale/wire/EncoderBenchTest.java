package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Encoder micro-benchmark (set PM_BENCH_OUT=file to run) and byte-for-byte equivalence with the reference encoder. */
class EncoderBenchTest {

    static Map<String, Object> frame(int n) {
        List<Object> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", String.format("%08x-0000-4000-8000-%012x", i, i));
            p.put("name", "Player" + i);
            p.put("world", "default");
            p.put("x", (long) (i * 31)); p.put("y", 6400L); p.put("z", (long) (-i * 17));
            p.put("yaw", 12345L); p.put("pitch", -300L); p.put("state", "alive");
            players.add(p);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", false);
        data.put("players", players);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sub", 1); f.put("seq", 7L); f.put("data", data);
        return f;
    }

    static Object random(Random r, int depth) {
        int t = r.nextInt(depth > 3 ? 7 : 10);
        switch (t) {
            case 0: return null;
            case 1: return r.nextBoolean();
            case 2: { long[] v = {0, 1, 127, 128, 255, 256, 65535, 65536, 0xffffffffL, 1L << 33, -1, -32, -33, -128, -129, -32768, -32769, Integer.MIN_VALUE, Integer.MIN_VALUE - 1L, Long.MIN_VALUE}; return v[r.nextInt(v.length)]; }
            case 3: return (r.nextDouble() - 0.5) * 1e6;
            case 4: { String[] s = {"", "a", "x".repeat(31), "x".repeat(32), "y".repeat(255), "z".repeat(256), "q".repeat(70000), "héllo", "日本語", "😀"}; return s[r.nextInt(s.length)]; }
            case 5: { byte[] b = new byte[r.nextInt(300)]; r.nextBytes(b); return b; }
            case 6: return 2.0;
            case 7: { List<Object> l = new ArrayList<>(); int n = r.nextInt(20); for (int i = 0; i < n; i++) l.add(random(r, depth + 1)); return l; }
            default: { Map<String, Object> m = new LinkedHashMap<>(); int n = r.nextInt(20); for (int i = 0; i < n; i++) m.put(random(r, 9) instanceof String s ? s + i : "k" + i, random(r, depth + 1)); return m; }
        }
    }

    @Test
    void identicalToReferenceEncoder() {
        Random r = new Random(7);
        for (int i = 0; i < 300; i++) {
            Object v = random(r, 0);
            assertArrayEquals(RefMsgpack.encode(v, false), Msgpack.encode(v, false), "plain " + i);
            assertArrayEquals(RefMsgpack.encode(v, true), Msgpack.encode(v, true), "canonical " + i);
        }
    }

    static double time(Runnable run, int iter) {
        for (int i = 0; i < 50; i++) run.run();
        double best = Double.MAX_VALUE;
        for (int rep = 0; rep < 5; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iter; i++) run.run();
            best = Math.min(best, (System.nanoTime() - t0) / 1e6 / iter);
        }
        return best;
    }

    @Test
    void bench() throws Exception {
        String out = System.getenv("PM_BENCH_OUT");
        Assumptions.assumeTrue(out != null && !out.isBlank());
        StringBuilder sb = new StringBuilder();
        Map<String, Object> f = frame(500);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "Weapon_Sword_Iron"); item.put("count", 1L); item.put("durability", 87.5);
        item.put("components", Map.of("a", 1L, "b", "two"));
        double a = time(() -> RefMsgpack.encode(f, false), 300);
        double b = time(() -> Msgpack.encode(f, false), 300);
        sb.append(String.format("encode position frame, 500 players   reference %.4f ms   current %.4f ms%n", a, b));
        double c = time(() -> RefMsgpack.encode(item, true), 20000);
        double d = time(() -> Msgpack.encode(item, true), 20000);
        sb.append(String.format("canonical item encode                 reference %.5f ms   current %.5f ms%n", c, d));
        double e = time(() -> Canonical.fingerprint(item), 20000);
        sb.append(String.format("fingerprint(item)                     %.5f ms%n", e));
        Files.writeString(Path.of(out), sb.toString(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }
}

package com.playermanager.hytale.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link MsgpackSize} must equal the length of what {@link Msgpack#encode} writes, in both modes. */
class MsgpackSizeTest {

    private static void same(Object v) {
        assertEquals(Msgpack.encode(v, false).length, MsgpackSize.of(v, false));
        assertEquals(Msgpack.encode(v, true).length, MsgpackSize.of(v, true));
    }

    @Test
    void scalarsAtEveryWidthBoundary() {
        long[] ints = {0, 1, 127, 128, 255, 256, 65535, 65536, 4294967295L, 4294967296L, -1, -32, -33, -128, -129,
                -32768, -32769, Integer.MIN_VALUE, Integer.MIN_VALUE - 1L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long i : ints) {
            same(i);
            same((int) i);
        }
        double[] doubles = {0.0, -0.0, 1.0, 2.5, -3.0, 9.007199254740992E15, 9.007199254740994E15, 1e300,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4294967296.0, -4294967297.0};
        for (double d : doubles) {
            same(d);
            same((float) d);
        }
        same(null);
        same(true);
        same(false);
    }

    @Test
    void stringsBinariesAndHeaders() {
        for (int n : new int[] {0, 1, 31, 32, 255, 256, 65535, 65536, 70_000}) {
            same("x".repeat(n));
            same(new byte[n]);
        }
        List<Object> list = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        for (int n : new int[] {0, 1, 15, 16, 65535, 65536}) {
            while (list.size() < n) {
                list.add((long) list.size());
                map.put("k" + map.size(), null);
            }
            same(list);
            same(map);
            same(list.toArray());
        }
    }

    @Test
    void randomStringsIncludingUnpairedSurrogates() {
        Random r = new Random(7);
        for (int i = 0; i < 2000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = r.nextInt(40);
            for (int j = 0; j < n; j++) {
                sb.append(switch (r.nextInt(6)) {
                    case 0 -> (char) r.nextInt(0x80);
                    case 1 -> (char) (0x80 + r.nextInt(0x780));
                    case 2 -> (char) (0x800 + r.nextInt(0xd000 - 0x800));
                    case 3 -> (char) (0xd800 + r.nextInt(0x800)); // any surrogate, often unpaired
                    case 4 -> "😀";
                    default -> (char) (0xe000 + r.nextInt(0x2000));
                });
            }
            String s = sb.toString();
            assertEquals(s.getBytes(StandardCharsets.UTF_8).length, MsgpackSize.utf8Length(s), s);
            same(s);
            same(Map.of(s, s));
        }
    }

    @Test
    void nestedStructuresWithNullsAndMixedNumbers() {
        Random r = new Random(11);
        for (int i = 0; i < 500; i++) {
            same(random(r, 0));
        }
    }

    private static Object random(Random r, int depth) {
        int kind = r.nextInt(depth > 3 ? 6 : 8);
        return switch (kind) {
            case 0 -> null;
            case 1 -> r.nextBoolean();
            case 2 -> r.nextLong() >> r.nextInt(64);
            case 3 -> r.nextBoolean() ? (double) r.nextInt(1000) : r.nextDouble() * 1e6;
            case 4 -> "s".repeat(r.nextInt(300));
            case 5 -> new byte[r.nextInt(300)];
            case 6 -> {
                List<Object> l = new ArrayList<>();
                for (int i = r.nextInt(20); i > 0; i--) {
                    l.add(random(r, depth + 1));
                }
                yield l;
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = r.nextInt(20); i > 0; i--) {
                    m.put("key" + r.nextInt(100), random(r, depth + 1));
                }
                yield m;
            }
        };
    }

    @Test
    void failsLikeTheEncoder() {
        assertThrows(IllegalArgumentException.class, () -> MsgpackSize.of(new Object(), false));
        assertThrows(IllegalArgumentException.class, () -> MsgpackSize.of(Map.of(1, 2), false));
        assertThrows(IllegalArgumentException.class, () -> Msgpack.encode(Map.of(1, 2), false));
    }
}

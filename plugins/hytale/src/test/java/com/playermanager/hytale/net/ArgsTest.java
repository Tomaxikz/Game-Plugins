package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgsTest {
    static final String ID = FakeGame.ID;

    static Args args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return Args.of(m);
    }

    /** The message of the invalid_args error the call raises. */
    static String invalid(Runnable r) {
        BridgeError e = assertThrows(BridgeError.class, r::run);
        assertEquals("invalid_args", e.code());
        return e.getMessage();
    }

    @Test
    void nullAndAbsentAreMissing() {
        Args a = args("x", null);
        assertTrue(!a.has("x") && !a.has("y"));
        assertNull(a.optUuid("x"));
        assertNull(a.optLong("x", 0, "m"));
        assertNull(a.optStr("x", 0));
        assertNull(a.strings("x", 3, 3));
        assertNull(a.optMap("x"));
        assertSame(Args.EMPTY, a.map("x"));
        assertEquals(List.of(), a.slots("x", 8));
        assertEquals(true, a.bool("x", true));
        assertEquals(16.0, a.number("x", 16, 1, 32));
        assertSame(Args.EMPTY, Args.of(Map.of()));
    }

    @Test
    void uuidContainerAndSlot() {
        assertEquals(ID, args("id", ID).uuid("id"));
        assertEquals("id must be a lowercase hyphenated UUID", invalid(() -> args("id", ID.toUpperCase()).uuid("id")));
        assertEquals("id must be a lowercase hyphenated UUID", invalid(() -> args("id", 5L).uuid("id")));
        assertEquals("id must be a lowercase hyphenated UUID", invalid(() -> args().uuid("id")));
        assertEquals("Hotbar", args("c", "Hotbar").container("c"));
        assertEquals("c must be a container id", invalid(() -> args("c", "../x").container("c")));
        assertEquals(3, args("s", 3L).slot("s"));
        assertEquals(7, args("s", "7").slot("s"), "D4: decimal strings are slots too");
        assertEquals(65535, args("s", 65535L).slot("s"));
        for (Object bad : new Object[] {-1L, 65536L, "65536", "hotbar:0", "", 1.5, "1234567890"}) {
            assertEquals("s must be a non-negative slot index", invalid(() -> args("s", bad).slot("s")), String.valueOf(bad));
        }
    }

    @Test
    void booleansAndIntegers() {
        assertEquals(true, args("b", true).bool("b", false));
        assertEquals("b must be a boolean", invalid(() -> args("b", "true").bool("b", false)));
        assertEquals(250L, args("n", 250L).lng("n"));
        assertEquals("n is required (integer)", invalid(() -> args("n", 1.0).lng("n")));
        assertEquals("n is required (integer)", invalid(() -> args().lng("n")));
        assertEquals(0, args("sub", 0L).id("sub"));
        assertEquals("sub is required (integer)", invalid(() -> args("sub", -1L).id("sub")));
        assertEquals("sub is required (integer)", invalid(() -> args("sub", 1L << 31).id("sub")));
        assertEquals(5L, args("limit", 5L).optLong("limit", 1, "limit must be a positive integer"));
        assertEquals("limit must be a positive integer", invalid(() -> args("limit", 0L).optLong("limit", 1,
                "limit must be a positive integer")));
        assertEquals("limit must be a positive integer", invalid(() -> args("limit", "5").optLong("limit", 1,
                "limit must be a positive integer")));
    }

    @Test
    void numbersAreRangeCheckedAndFinite() {
        assertEquals(8.0, args("radius", 8L).number("radius", 16, 1, 32));
        assertEquals(1.5, args("radius", 1.5).number("radius", 16, 1, 32));
        assertEquals("radius must be a number", invalid(() -> args("radius", "8").number("radius", 16, 1, 32)));
        assertEquals("radius must be a number",
                invalid(() -> args("radius", Double.NaN).number("radius", 16, 1, 32)));
        assertEquals("radius must be between 1 and 32", invalid(() -> args("radius", 33L).number("radius", 16, 1, 32)));
        assertEquals("radius must be between 1 and 32", invalid(() -> args("radius", 0.5).number("radius", 16, 1, 32)));
        assertEquals("x must be between 0.5 and 2", invalid(() -> args("x", 3L).number("x", 1, 0.5, 2)));
    }

    @Test
    void stringsAndLists() {
        assertEquals("abc", args("c", "abc").optStr("c", 3));
        assertEquals("c must be a string", invalid(() -> args("c", "abcd").optStr("c", 3)));
        assertEquals("c must be a string", invalid(() -> args("c", 5L).optStr("c", 0)));
        assertEquals("x".repeat(1000), args("c", "x".repeat(1000)).optStr("c", 0));
        assertEquals("action is required", invalid(() -> args("action", "").str("action", "action is required")));
        assertEquals("action is required", invalid(() -> args().str("action", "action is required")));
        assertEquals(List.of("a", "b"), args("w", List.of("a", "b")).strings("w", 2, 5));
        assertEquals("w must be an array of strings", invalid(() -> args("w", "x").strings("w", 2, 5)));
        assertEquals("w must be an array of strings", invalid(() -> args("w", List.of("a", "b", "c")).strings("w", 2, 5)));
        assertEquals("w must be an array of strings", invalid(() -> args("w", List.of("")).strings("w", 2, 5)));
        assertEquals("w must be an array of strings", invalid(() -> args("w", List.of("toolong")).strings("w", 2, 5)));
        assertEquals("w must be an array of strings", invalid(() -> args("w", List.of(1L)).strings("w", 2, 5)));
        assertEquals(List.of(0, 7), args("p", List.of(0L, "7")).slots("p", 8));
        assertEquals("p must be an array of up to 2 indices",
                invalid(() -> args("p", List.of(0L, 1L, 2L)).slots("p", 2)));
        assertEquals("p must be a non-negative slot index", invalid(() -> args("p", List.of(-1L)).slots("p", 2)));
    }

    @Test
    void nestedMaps() {
        Args a = args("args", Map.of("k", 1L));
        assertEquals(1L, a.map("args").get("k"));
        assertEquals(Map.of("k", 1L), a.optMap("args"));
        assertEquals("args must be a map", invalid(() -> args("args", List.of()).map("args")));
        assertEquals("expect must be a map", invalid(() -> args("expect", 1L).optMap("expect")));
    }
}

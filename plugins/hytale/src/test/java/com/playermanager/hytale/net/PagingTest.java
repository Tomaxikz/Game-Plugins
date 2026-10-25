package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PagingTest {
    @Test
    void offsetCursor() {
        assertEquals(0, Paging.offset(null, 2, 10, false));
        assertEquals(0, Paging.offset("0", 2, 10, false));
        assertEquals(9, Paging.offset("9", 2, 10, false));
        assertEquals(10, Paging.offset("10", 2, 10, true), "marker lists may end exactly at the size");
        for (String bad : new String[] {"10", "99", "-1", "1x", "", " 1", "1\n", "007"}) {
            assertEquals("invalid_args", code(() -> Paging.offset(bad, 2, 10, false)), bad);
        }
        assertEquals("invalid_args", code(() -> Paging.offset("11", 2, 10, true)));
        assertEquals("invalid_args", code(() -> Paging.offset(5L, 2, 10, false)));
        assertEquals("invalid_args", code(() -> Paging.offset("1234567", 6, 10_000_000, true)));
        assertEquals(123456, Paging.offset("123456", 6, 200_000, true));
    }

    @Test
    void moreAndCursorOnlyWhenItemsRemain() {
        Map<String, Object> data = new LinkedHashMap<>();
        Paging.more(data, 5, 5);
        assertTrue(data.isEmpty());
        Paging.more(data, 3, 5);
        assertEquals(true, data.get("more"));
        assertEquals("3", data.get("cursor"));
        assertEquals(java.util.List.of("more", "cursor"), new java.util.ArrayList<>(data.keySet()));
    }

    @Test
    void budgetAlwaysAdmitsTheFirstItem() {
        Paging.Budget b = new Paging.Budget(100);
        assertTrue(b.fits(1000), "one item larger than the budget still goes out alone");
        b.take(1000);
        assertFalse(b.fits(1));
        Paging.Budget c = new Paging.Budget(100);
        c.take(60);
        assertTrue(c.fits(40));
        assertFalse(c.fits(41));
        c.take(40);
        assertEquals(100, c.used());
        assertEquals(2, c.count());
    }

    static String code(Runnable r) {
        return assertThrows(BridgeError.class, r::run).code();
    }
}

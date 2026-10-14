package dev.playermanager.bridge.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The per-class (name, parameter count) method cache must resolve exactly what the uncached lookup did, and remember misses. */
class ReflectCacheTest {
    @Test void resolvesOverloadsByParameterCount() {
        Method m0 = Reflect.method(String.class, "substring", 1);
        Method m2 = Reflect.method(String.class, "substring", 2);
        assertNotNull(m0);
        assertNotNull(m2);
        assertEquals(1, m0.getParameterCount());
        assertEquals(2, m2.getParameterCount());
        assertSame(m0, Reflect.method(String.class, "substring", 1));        // cache hit returns the same Method
        assertSame(m2, Reflect.method(String.class, "substring", 2));
    }

    @Test void missesAreRemembered() {
        assertNull(Reflect.method(String.class, "noSuchMethod", 0));
        assertNull(Reflect.method(String.class, "noSuchMethod", 0));
        assertNull(Reflect.method(String.class, "length", 3));
        assertNotNull(Reflect.method(String.class, "length", 0));          // a miss for one count does not hide another
    }

    @Test void callWorksThroughTheCache() {
        assertEquals(5, Reflect.call("hello", "length"));
        assertEquals("ello", Reflect.call("hello", "substring", 1));
        assertEquals("el", Reflect.call("hello", "substring", 1, 3));
        assertNull(Reflect.call("hello", "nothing"));
        assertNull(Reflect.call(null, "length"));
    }

    @Test void differentClassesDoNotShareEntries() {
        assertNotNull(Reflect.method(List.class, "size", 0));
        assertNull(Reflect.method(Integer.class, "size", 0));
    }

    @Test void cachedDateFormatMatchesSimpleDateFormat() {
        Date d = new Date(1760280100123L);
        String expect = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(d);
        assertEquals(expect, RosterFiles.formatDate(d));
        assertEquals(expect, RosterFiles.formatDate(new Date(d.getTime())));      // second call from the cache
    }
}

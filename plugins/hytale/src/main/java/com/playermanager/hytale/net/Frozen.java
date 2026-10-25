package com.playermanager.hytale.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deep read-only copies of reply data. Cached and memoised results are handed to many connections, so nothing may
 * mutate them afterwards. Iteration order (the wire key order) and null values are preserved; byte arrays and
 * scalars are shared as they are.
 */
final class Frozen {
    private Frozen() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> of(Map<String, Object> m) {
        return (Map<String, Object>) freeze(m);
    }

    private static Object freeze(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>(Math.max(4, m.size() * 2));
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(e.getKey(), freeze(e.getValue()));
            }
            return Collections.unmodifiableMap(out);
        }
        if (v instanceof Set<?> s) {
            Set<Object> out = new LinkedHashSet<>();
            for (Object o : s) {
                out.add(freeze(o));
            }
            return Collections.unmodifiableSet(out);
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) {
                out.add(freeze(o));
            }
            return Collections.unmodifiableList(out);
        }
        return v;
    }
}

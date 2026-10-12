package com.playermanager.hytale.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ActionSpec / Field builders (operations.md §1: choices, min and max are always present). */
final class Specs {
    private Specs() {
    }

    static Map<String, Object> field(String key, String label, String kind, List<String> choices,
                                     Number min, Number max) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("kind", kind);
        f.put("choices", choices == null ? List.of() : choices);
        f.put("min", min);
        f.put("max", max);
        return f;
    }

    static Map<String, Object> text(String key, String label) {
        return field(key, label, "text", null, null, null);
    }

    static Map<String, Object> number(String key, String label, Number min, Number max) {
        return field(key, label, "number", null, min, max);
    }

    static Map<String, Object> select(String key, String label, List<String> choices) {
        return field(key, label, "select", choices, null, null);
    }

    @SafeVarargs
    static Map<String, Object> action(String id, boolean target, boolean destructive, boolean offline,
                                      Map<String, Object>... fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("label", id);
        a.put("target", target);
        a.put("destructive", destructive);
        a.put("offline", offline);
        a.put("fields", new ArrayList<>(List.of(fields)));
        return a;
    }
}

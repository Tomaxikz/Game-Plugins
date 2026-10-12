package dev.playermanager.bridge.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One extra input of an {@link ActionSpec} (operations.md section 1 "Field object"). */
public final class Field {
    public enum Kind { text, integer, number, select, identifier, target, item }

    public final String key;
    public final String label;
    public final Kind kind;
    public final List<String> choices;
    public final Double min;
    public final Double max;
    /** Not advertised; the value is optional when false. Required fields are enforced by the handler. */
    public final boolean required;
    /** For {@link Kind#text}: also accept integers (slot keys). */
    public final boolean allowNumber;
    public final int maxLength;

    private Field(String key, String label, Kind kind, List<String> choices, Double min, Double max, boolean required, boolean allowNumber, int maxLength) {
        this.key = key; this.label = label; this.kind = kind;
        this.choices = Collections.unmodifiableList(new ArrayList<String>(choices));
        this.min = min; this.max = max; this.required = required; this.allowNumber = allowNumber; this.maxLength = maxLength;
    }

    public static Field text(String key, String label) { return new Field(key, label, Kind.text, Collections.<String>emptyList(), null, null, false, false, 256); }
    public static Field slot(String key, String label) { return new Field(key, label, Kind.text, Collections.<String>emptyList(), null, null, false, true, 32); }
    public static Field integer(String key, String label, double min, double max) { return new Field(key, label, Kind.integer, Collections.<String>emptyList(), min, max, false, false, 0); }
    public static Field number(String key, String label, Double min, Double max) { return new Field(key, label, Kind.number, Collections.<String>emptyList(), min, max, false, false, 0); }
    public static Field select(String key, String label, String... choices) { return new Field(key, label, Kind.select, Arrays.asList(choices), null, null, false, false, 0); }
    public static Field identifier(String key, String label) { return new Field(key, label, Kind.identifier, Collections.<String>emptyList(), null, null, false, false, 128); }
    public static Field target(String key, String label) { return new Field(key, label, Kind.target, Collections.<String>emptyList(), null, null, false, false, 0); }
    public static Field item(String key, String label) { return new Field(key, label, Kind.item, Collections.<String>emptyList(), null, null, false, false, 0); }

    public Field required() { return new Field(key, label, kind, choices, min, max, true, allowNumber, maxLength); }
    public Field maxLength(int n) { return new Field(key, label, kind, choices, min, max, required, allowNumber, n); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("label", label);
        m.put("kind", kind.name());
        m.put("choices", choices);
        m.put("min", min);
        m.put("max", max);
        return m;
    }
}

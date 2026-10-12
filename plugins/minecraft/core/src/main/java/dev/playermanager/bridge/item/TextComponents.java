package dev.playermanager.bridge.item;

import dev.playermanager.bridge.codec.Json;
import dev.playermanager.bridge.nbt.Nbt;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts Minecraft text components (JSON strings or NBT-structured) to {@code {plain, json?}}. */
public final class TextComponents {
    private TextComponents() {}

    /** @return {@code {plain, json?}} or null if the value is empty/unusable. */
    public static Map<String, Object> toText(Object v) {
        if (v == null) return null;
        Object json = null;
        String plain;
        if (v instanceof String) {
            String s = (String) v;
            String t = s.trim();
            if (t.startsWith("{") || t.startsWith("[") || (t.startsWith("\"") && t.endsWith("\""))) {
                try {
                    json = Json.parse(t);
                    plain = plain(json);
                } catch (RuntimeException e) {
                    plain = stripCodes(s);
                    json = null;
                }
            } else {
                plain = stripCodes(s);
            }
        } else {
            json = Nbt.toFriendly(v);
            plain = plain(json);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("plain", plain);
        if (json != null && !(json instanceof String)) m.put("json", json);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static String plain(Object c) {
        StringBuilder sb = new StringBuilder();
        append(sb, c, 0);
        return stripCodes(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder sb, Object c, int depth) {
        if (c == null || depth > 32) return;
        if (c instanceof String) { sb.append((String) c); return; }
        if (c instanceof Number || c instanceof Boolean) { sb.append(c); return; }
        if (c instanceof List) { for (Object o : (List<Object>) c) append(sb, o, depth + 1); return; }
        if (c instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) c;
            Object t = m.get("text");
            if (t != null) append(sb, t, depth + 1);
            else if (m.get("translate") != null) sb.append(m.get("translate"));
            else if (m.get("keybind") != null) sb.append(m.get("keybind"));
            else if (m.get("selector") != null) sb.append(m.get("selector"));
            else if (m.get("score") instanceof Map) {
                Object val = ((Map<String, Object>) m.get("score")).get("value");
                if (val != null) sb.append(val);
            }
            Object extra = m.get("extra");
            if (extra != null) append(sb, extra, depth + 1);
        }
    }

    /** Removes {@code §x} / {@code &}-less legacy colour codes. */
    public static String stripCodes(String s) {
        if (s.indexOf('§') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}

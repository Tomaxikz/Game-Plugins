package com.playermanager.hytale.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer for bridge.json / config.json (no dependencies). */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("trailing JSON content");
        }
        return v;
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("JSON nesting too deep");
        }
        if (i >= s.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("bad JSON literal at " + i);
        }
        i += word.length();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected JSON key at " + i);
            }
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw new IllegalArgumentException("expected ':' at " + i);
            }
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            throw new IllegalArgumentException("expected ',' or '}' at " + i);
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            throw new IllegalArgumentException("expected ',' or ']' at " + i);
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unterminated JSON string");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new IllegalArgumentException("bad JSON escape");
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("bad JSON value at " + start);
        }
        if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
            return Double.parseDouble(t);
        }
        return Long.parseLong(t);
    }

    /** Serialises maps (String keys), lists, strings, numbers, booleans and null. */
    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        writeTo(b, v);
        return b.toString();
    }

    private static void writeTo(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            b.append('"');
            for (int k = 0; k < str.length(); k++) {
                char c = str.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
            b.append('"');
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Double d) {
            b.append(Double.isFinite(d) ? d.toString() : "null");
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                writeTo(b, String.valueOf(e.getKey()));
                b.append(':');
                writeTo(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                writeTo(b, o);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("unsupported JSON type " + v.getClass());
        }
    }
}

package dev.playermanager.bridge.codec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader/writer (config.json, bridge.json, resource-pack json). Numbers: Long when integral, else Double. */
public final class Json {
    private Json() {}

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new JsonException("trailing data at " + p.i);
        return v;
    }

    /** Lenient: allows comments and trailing commas (resource packs sometimes contain them). */
    public static Object parseLenient(String s) {
        P p = new P(s);
        p.lenient = true;
        p.ws();
        Object v = p.value(0);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new JsonException("expected object");
        return (Map<String, Object>) o;
    }

    public static String write(Object v) { StringBuilder sb = new StringBuilder(); write(sb, v, -1, 0); return sb.toString(); }

    public static String writePretty(Object v) { StringBuilder sb = new StringBuilder(); write(sb, v, 2, 0); sb.append('\n'); return sb.toString(); }

    private static void indent(StringBuilder sb, int ind, int depth) {
        if (ind < 0) return;
        sb.append('\n');
        for (int i = 0; i < ind * depth; i++) sb.append(' ');
    }

    private static void write(StringBuilder sb, Object v, int ind, int depth) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
        } else if (v instanceof Number) sb.append(((Number) v).longValue());
        else if (v instanceof CharSequence) quote(sb, v.toString());
        else if (v instanceof byte[]) quote(sb, Hashes.hex((byte[]) v));
        else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                indent(sb, ind, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(ind >= 0 ? ": " : ":");
                write(sb, e.getValue(), ind, depth + 1);
            }
            indent(sb, ind, depth);
            sb.append('}');
        } else if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            if (c.isEmpty()) { sb.append("[]"); return; }
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                indent(sb, ind, depth + 1);
                write(sb, o, ind, depth + 1);
            }
            indent(sb, ind, depth);
            sb.append(']');
        } else quote(sb, v.toString());
    }

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class P {
        final String s; int i; boolean lenient;
        P(String s) { this.s = s; if (s.length() > 0 && s.charAt(0) == '﻿') i = 1; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else if (lenient && c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n') i++;
                } else if (lenient && c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int e = s.indexOf("*/", i + 2);
                    i = e < 0 ? s.length() : e + 2;
                } else break;
            }
        }

        Object value(int depth) {
            if (depth > 200) throw new JsonException("too deep");
            if (i >= s.length()) throw new JsonException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                ws();
                if (peek('}')) { i++; return m; }
                while (true) {
                    ws();
                    if (lenient && peek('}')) { i++; return m; }
                    if (!peek('"')) throw new JsonException("expected key at " + i);
                    String k = str();
                    ws();
                    expect(':');
                    ws();
                    m.put(k, value(depth + 1));
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect('}');
                    return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<Object>();
                ws();
                if (peek(']')) { i++; return l; }
                while (true) {
                    ws();
                    if (lenient && peek(']')) { i++; return l; }
                    l.add(value(depth + 1));
                    ws();
                    if (peek(',')) { i++; continue; }
                    expect(']');
                    return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new JsonException("unexpected '" + c + "' at " + i);
            String n = s.substring(st, i);
            try {
                if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.valueOf(Long.parseLong(n));
                return Double.valueOf(Double.parseDouble(n));
            } catch (NumberFormatException e) {
                throw new JsonException("bad number " + n);
            }
        }

        boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
        void expect(char c) { if (!peek(c)) throw new JsonException("expected '" + c + "' at " + i); i++; }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new JsonException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) throw new JsonException("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new JsonException("bad \\u escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
    }

    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JsonException(String m) { super(m); }
    }
}

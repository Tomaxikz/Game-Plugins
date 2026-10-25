package com.playermanager.hytale;

import com.playermanager.hytale.wire.Msgpack;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.opentest4j.AssertionFailedError;

/**
 * Golden-file support for the guard tests that pin the plugin's observable behaviour.
 *
 * <p>Files live in {@code src/test/resources/golden} (resolved from the Gradle project directory, which is the test
 * working directory). A missing golden is written and the session then fails with "golden written, re-run"; an
 * existing one is compared byte for byte. A mismatch prints a readable JSON diff. Every check in a session runs
 * before the session fails, so one run writes every missing file.
 *
 * <p>To accept a deliberate behaviour change, delete the affected file and re-run twice.
 */
public final class Golden {
    private static final Path DIR = Path.of("src/test/resources/golden").toAbsolutePath();

    private Golden() {
    }

    /** Collects the outcome of several golden checks and fails once, at {@link #close()}. */
    public static final class Session implements AutoCloseable {
        private final List<String> written = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        /**
         * Pins a wire encoding. {@code steps} is a list of maps; it is encoded with the project's non-canonical
         * msgpack encoder (insertion order of every map is part of the golden) and stored as hex.
         */
        public void hex(String name, List<Object> steps) {
            byte[] bytes = Msgpack.encode(steps, false);
            Path file = DIR.resolve(name + ".hex");
            String actual = wrapHex(bytes);
            if (!Files.exists(file)) {
                write(file, actual);
                written.add(file.toString());
                return;
            }
            String expected = read(file);
            if (!strip(expected).equals(strip(actual))) {
                failures.add(name + ".hex differs from the golden\n" + hexDiff(expected, bytes));
            }
        }

        /**
         * Like {@link #hex}, for steps that carry PNG bytes. Deflate output depends on the zlib build the JDK links
         * (zlib vs zlib-ng: same pixels, different bytes), so a PNG is pinned by its size and decoded pixels, and
         * every 16-hex hash (asset, tile and manifest hashes are computed over PNG bytes) by a token numbered in
         * first-appearance order, which keeps "same hash here as there" pinned. A recorded raw frame whose decoded
         * copy changed is dropped; the decoded copy is still pinned. Hash values themselves are pinned by the
         * vector tests (CanonicalTest, AssetServiceTest, ItemRecordGoldenTest).
         */
        public void hexPngNeutral(String name, List<Object> steps) {
            hex(name, new PngNeutral().list(steps));
        }

        /** Pins a JSON-shaped value, pretty-printed with map insertion order preserved. */
        public void json(String name, Object value) {
            Path file = DIR.resolve(name + ".json");
            String actual = pretty(value) + "\n";
            if (!Files.exists(file)) {
                write(file, actual);
                written.add(file.toString());
                return;
            }
            String expected = read(file);
            if (!expected.equals(actual)) {
                failures.add(name + ".json differs from the golden\n" + lineDiff(expected, actual));
            }
        }

        @Override
        public void close() {
            if (!failures.isEmpty()) {
                throw new AssertionFailedError(String.join("\n\n", failures)
                        + (written.isEmpty() ? "" : "\n(also golden written: " + written + ")"));
            }
            if (!written.isEmpty()) {
                throw new AssertionFailedError("golden written, re-run: " + written);
            }
        }
    }

    // ---- zlib-independent normalisation -------------------------------------------------------

    private static final class PngNeutral {
        private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[0-9a-f]{16}");
        private final Map<String, String> tokens = new java.util.HashMap<>();
        private boolean changed;

        List<Object> list(Iterable<?> in) {
            List<Object> out = new ArrayList<>();
            for (Object o : in) {
                out.add(value(o));
            }
            return out;
        }

        Object value(Object v) {
            if (v instanceof byte[] bin && isPng(bin)) {
                changed = true;
                return png(bin);
            }
            if (v instanceof String s && HASH.matcher(s).matches()) {
                changed = true;
                return tokens.computeIfAbsent(s, k -> "<hash#" + (tokens.size() + 1) + ">");
            }
            if (v instanceof Map<?, ?> m) {
                return map(m);
            }
            if (v instanceof Iterable<?> it) {
                return list(it);
            }
            return v;
        }

        private Map<String, Object> map(Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            if (m.get("raw") instanceof byte[] && m.containsKey("decoded")) {
                // a recorded socket frame: keep the exact bytes unless the frame carries PNG-derived data
                boolean outer = changed;
                changed = false;
                Object decoded = value(m.get("decoded"));
                boolean dirty = changed;
                changed = outer || dirty;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    out.put(k, switch (k) {
                        case "raw" -> dirty ? "<raw omitted: frame carries png-derived data>" : e.getValue();
                        case "decoded" -> decoded;
                        default -> value(e.getValue());
                    });
                }
                return out;
            }
            m.forEach((k, val) -> out.put(String.valueOf(k), value(val)));
            return out;
        }

        private static boolean isPng(byte[] b) {
            return b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        }

        private static String png(byte[] bin) {
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bin));
                if (img == null) {
                    return "<png undecodable>";
                }
                int w = img.getWidth();
                int h = img.getHeight();
                java.nio.ByteBuffer px = java.nio.ByteBuffer.allocate(w * h * 4);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        px.putInt(img.getRGB(x, y));
                    }
                }
                byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(px.array());
                return "<png " + w + "x" + h + " argb " + HexFormat.of().formatHex(d, 0, 8) + ">";
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---- hex ---------------------------------------------------------------------------------

    static String wrapHex(byte[] bytes) {
        String h = HexFormat.of().formatHex(bytes);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length(); i += 128) {
            b.append(h, i, Math.min(h.length(), i + 128)).append('\n');
        }
        return b.toString();
    }

    private static String strip(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String hexDiff(String expectedHex, byte[] actual) {
        StringBuilder out = new StringBuilder();
        Object exp;
        Object act;
        try {
            exp = Msgpack.decode(HexFormat.of().parseHex(strip(expectedHex)));
            act = Msgpack.decode(actual);
        } catch (RuntimeException e) {
            return "(could not decode for a diff: " + e + ")";
        }
        List<?> el = exp instanceof List<?> l ? l : List.of(exp);
        List<?> al = act instanceof List<?> l ? l : List.of(act);
        int n = Math.max(el.size(), al.size());
        for (int i = 0; i < n; i++) {
            String e = i < el.size() ? compact(el.get(i)) : "<missing>";
            String a = i < al.size() ? compact(al.get(i)) : "<missing>";
            if (!e.equals(a)) {
                out.append("  step ").append(i).append(" (").append(label(i < al.size() ? al.get(i) : el.get(i)))
                        .append(")\n    expected: ").append(e).append("\n    actual:   ").append(a).append('\n');
            }
        }
        if (out.length() == 0) {
            out.append("  decoded values are equal: only the encoding (key order, integer/float width) differs\n");
            out.append("  expected hex: ").append(strip(expectedHex)).append('\n');
            out.append("  actual hex:   ").append(HexFormat.of().formatHex(actual)).append('\n');
        }
        return out.toString();
    }

    private static String label(Object step) {
        return step instanceof Map<?, ?> m && m.get("case") != null ? String.valueOf(m.get("case")) : "?";
    }

    // ---- JSON rendering ----------------------------------------------------------------------

    /** Single-line JSON; byte arrays are shown as {@code <bin:hex>}. */
    static String compact(Object v) {
        StringBuilder b = new StringBuilder();
        render(b, v, -1, 0);
        return b.toString();
    }

    static String pretty(Object v) {
        StringBuilder b = new StringBuilder();
        render(b, v, 2, 0);
        return b.toString();
    }

    private static boolean scalar(Object o) {
        return !(o instanceof Map<?, ?>) && !(o instanceof Iterable<?>);
    }

    private static void render(StringBuilder b, Object v, int indent, int depth) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String s) {
            b.append(quote(s));
        } else if (v instanceof byte[] bin) {
            b.append("\"<bin:").append(HexFormat.of().formatHex(bin)).append(">\"");
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            b.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            b.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                newline(b, indent, depth + 1);
                b.append(quote(String.valueOf(e.getKey()))).append(indent < 0 ? ":" : ": ");
                render(b, e.getValue(), indent, depth + 1);
            }
            newline(b, indent, depth);
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            List<Object> items = new ArrayList<>();
            it.forEach(items::add);
            if (items.isEmpty()) {
                b.append("[]");
                return;
            }
            boolean inline = indent < 0 || items.stream().allMatch(Golden::scalar);
            b.append('[');
            boolean first = true;
            for (Object o : items) {
                if (!first) {
                    b.append(inline ? (indent < 0 ? "," : ", ") : ",");
                }
                first = false;
                if (!inline) {
                    newline(b, indent, depth + 1);
                }
                render(b, o, indent, depth + 1);
            }
            if (!inline) {
                newline(b, indent, depth);
            }
            b.append(']');
        } else {
            b.append(quote(v.toString()));
        }
    }

    private static void newline(StringBuilder b, int indent, int depth) {
        if (indent < 0) {
            return;
        }
        b.append('\n');
        b.append(" ".repeat(indent * depth));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static String lineDiff(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < Math.max(e.length, a.length) && shown < 40; i++) {
            String x = i < e.length ? e[i] : "<missing>";
            String y = i < a.length ? a[i] : "<missing>";
            if (!x.equals(y)) {
                out.append("  line ").append(i + 1).append("\n    expected: ").append(x).append("\n    actual:   ")
                        .append(y).append('\n');
                shown++;
            }
        }
        return out.toString();
    }

    // ---- files -------------------------------------------------------------------------------

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write " + file, e);
        }
    }
}

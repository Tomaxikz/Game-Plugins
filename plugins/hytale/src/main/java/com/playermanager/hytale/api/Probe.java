package com.playermanager.hytale.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Startup reflection probes (hytale.md §8). {@code probes.txt} lists, per capability, the exact API members the code
 * relies on; a capability is verified only if every member resolves on the RUNNING server (and every capability it
 * requires is verified). The code itself is compiled against the pinned build, so on a different build a missing
 * member turns the capability off instead of failing later with NoSuchMethodError.
 *
 * <p>File format, one entry per line ({@code #} starts a comment):
 * <pre>
 * [capability] requires=other,ids          section header, requires= is optional (earlier sections only)
 * type          pkg.Class
 * method        pkg.Class name(param,types)  public method, inherited ones count
 * ctor          pkg.Class (param,types)      public constructor
 * declaredCtor  pkg.Class (param,types)      constructor of any visibility (a protected one our subclass calls)
 * field         pkg.Class NAME               public field
 * declaredField pkg.Class name               non-public field reached by reflection (made accessible)
 * declaredMethod pkg.Class name(param,types) non-public method reached by reflection (made accessible)
 * </pre>
 * Class and parameter names are binary names ({@code pkg.Outer$Inner}, {@code int}, {@code java.util.UUID}).
 * {@code ProbeCoverageTest} checks that every game member the compiled bridge code references is listed.
 */
final class Probe {
    static final String RESOURCE = "/probes.txt";

    /** One probed member. */
    record Member(String kind, String owner, String name, List<String> params) {
        /** Canonical text, also used by ProbeCoverageTest: {@code owner#name(params)}, {@code owner#name} for fields. */
        String key() {
            return switch (kind) {
                case "field", "declaredField" -> owner + "#" + name;
                case "type" -> owner;
                default -> Probe.key(owner, name, params);
            };
        }

        @Override
        public String toString() {
            return kind + " " + key();
        }

        boolean check(ClassLoader cl) throws Exception {
            Class<?> c = Class.forName(owner, false, cl);
            switch (kind) {
                case "type":
                    return true;
                case "method":
                    for (Method m : c.getMethods()) {
                        if (m.getName().equals(name) && names(m.getParameterTypes()).equals(params)) {
                            return true;
                        }
                    }
                    return false;
                case "ctor":
                    for (Constructor<?> k : c.getConstructors()) {
                        if (names(k.getParameterTypes()).equals(params)) {
                            return true;
                        }
                    }
                    return false;
                case "declaredCtor":
                    for (Constructor<?> k : c.getDeclaredConstructors()) {
                        if (names(k.getParameterTypes()).equals(params)) {
                            return true;
                        }
                    }
                    return false;
                case "field":
                    c.getField(name);
                    return true;
                case "declaredField":
                    c.getDeclaredField(name).setAccessible(true);
                    return true;
                case "declaredMethod":
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(name) && names(m.getParameterTypes()).equals(params)) {
                            m.setAccessible(true);
                            return true;
                        }
                    }
                    return false;
                default:
                    throw new IllegalStateException("kind " + kind);
            }
        }
    }

    static String key(String owner, String name, List<String> params) {
        return owner + "#" + name + "(" + String.join(",", params) + ")";
    }

    private record Section(String id, List<String> requires, List<Member> members) {
    }

    private final ClassLoader loader;
    private final Map<String, Section> sections;
    private final Map<String, Boolean> results = new HashMap<>();
    private final Map<String, List<String>> failures = new LinkedHashMap<>();

    private Probe(ClassLoader loader, Map<String, Section> sections) {
        this.loader = loader;
        this.sections = sections;
    }

    /** The bridge's own probes.txt, resolved against {@code loader} (the server's class loader). */
    static Probe load(ClassLoader loader) {
        return parse(loader, resourceText());
    }

    static String resourceText() {
        try (InputStream in = Probe.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the bridge jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    static Probe parse(ClassLoader loader, String text) {
        Map<String, Section> out = new LinkedHashMap<>();
        Section cur = null;
        int n = 0;
        for (String raw : text.split("\n")) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    String id = line.substring(1, end);
                    List<String> requires = new ArrayList<>();
                    String rest = line.substring(end + 1).strip();
                    if (rest.startsWith("requires=")) {
                        for (String r : rest.substring("requires=".length()).split(",")) {
                            if (!out.containsKey(r.strip())) {
                                throw new IllegalArgumentException("requires unknown (or later) section " + r);
                            }
                            requires.add(r.strip());
                        }
                    } else if (!rest.isEmpty()) {
                        throw new IllegalArgumentException("unexpected text after the header");
                    }
                    if (out.containsKey(id)) {
                        throw new IllegalArgumentException("duplicate section " + id);
                    }
                    cur = new Section(id, requires, new ArrayList<>());
                    out.put(id, cur);
                } else if (cur == null) {
                    throw new IllegalArgumentException("entry before the first section");
                } else {
                    cur.members().add(member(line));
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("probes.txt line " + n + ": " + e.getMessage(), e);
            }
        }
        return new Probe(loader, out);
    }

    private static Member member(String line) {
        String[] t = line.split("\\s+");
        String kind = t[0];
        switch (kind) {
            case "type":
                return new Member(kind, t[1], "", List.of());
            case "field", "declaredField":
                return new Member(kind, t[1], t[2], List.of());
            case "ctor", "declaredCtor":
                return new Member(kind, t[1], "<init>", params(t[2]));
            case "method", "declaredMethod":
                int p = t[2].indexOf('(');
                return new Member(kind, t[1], t[2].substring(0, p), params(t[2].substring(p)));
            default:
                throw new IllegalArgumentException("unknown kind " + kind);
        }
    }

    /** {@code (a,b)} -> [a, b]. */
    private static List<String> params(String paren) {
        if (!paren.startsWith("(") || !paren.endsWith(")")) {
            throw new IllegalArgumentException("parameters must look like (a,b): " + paren);
        }
        String inner = paren.substring(1, paren.length() - 1);
        return inner.isEmpty() ? List.of() : List.of(inner.split(","));
    }

    private static List<String> names(Class<?>[] types) {
        List<String> out = new ArrayList<>(types.length);
        for (Class<?> t : types) {
            out.add(t.getName());
        }
        return out;
    }

    // ---- evaluation --------------------------------------------------------------------------

    /**
     * True when every member of the capability resolves and every capability it requires is verified. A capability
     * whose requirement failed is not evaluated (the failure is already recorded under the requirement). Results are
     * remembered, so a capability is evaluated (and reported) once.
     */
    boolean ok(String capability) {
        Boolean known = results.get(capability);
        if (known != null) {
            return known;
        }
        Section s = sections.get(capability);
        boolean result = true;
        if (s == null) {
            failures.put(capability, List.of("no such probe section"));
            result = false;
        } else {
            for (String r : s.requires()) {
                if (!ok(r)) {
                    result = false;
                }
            }
            if (result) {
                List<String> bad = new ArrayList<>();
                for (Member m : s.members()) {
                    try {
                        if (!m.check(loader)) {
                            bad.add(m.toString());
                        }
                    } catch (Throwable t) {
                        bad.add(m + " (" + t.getClass().getSimpleName() + ")");
                    }
                }
                if (!bad.isEmpty()) {
                    failures.put(capability, bad);
                    result = false;
                }
            }
        }
        results.put(capability, result);
        return result;
    }

    /** Failed checks by capability, for bridge.log. */
    Map<String, List<String>> failures() {
        return failures;
    }

    /** Every section id in file order. */
    Set<String> capabilities() {
        return sections.keySet();
    }

    /** The members listed under one capability, in file order (empty for an unknown one). */
    List<Member> members(String capability) {
        Section s = sections.get(capability);
        return s == null ? List.of() : List.copyOf(s.members());
    }

    /** Keys of every method, constructor and field member of every section (ProbeCoverageTest). */
    Set<String> memberKeys() {
        Set<String> out = new LinkedHashSet<>();
        for (Section s : sections.values()) {
            for (Member m : s.members()) {
                if (!m.kind().equals("type") && !m.kind().equals("declaredField")
                        && !m.kind().equals("declaredMethod")) {
                    out.add(m.key());
                }
            }
        }
        return out;
    }
}

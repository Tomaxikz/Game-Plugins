package com.playermanager.hytale.net;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Expect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** action.run argument checks: the typed {@code args} against the advertised ActionSpec, and {@code expect}. */
final class ActionArgs {
    private static final int MAX_TEXT = 512;

    private ActionArgs() {
    }

    /**
     * Validates {@code args} against the spec's fields (kind / choices / min / max) and returns the cleaned map.
     * Unknown keys are dropped.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> validate(Map<String, Object> spec, Args args) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(spec.get("fields") instanceof List<?> fields)) {
            return out;
        }
        for (Object fo : fields) {
            Map<String, Object> f = (Map<String, Object>) fo;
            String key = (String) f.get("key");
            Object v = args.get(key);
            if (v == null) {
                continue;
            }
            switch ((String) f.get("kind")) {
                case "integer" -> {
                    if (!(v instanceof Long l)) {
                        throw BridgeError.invalid(key + " must be an integer");
                    }
                    checkRange(key, l.doubleValue(), f);
                }
                case "number" -> {
                    if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                        throw BridgeError.invalid(key + " must be a finite number");
                    }
                    checkRange(key, n.doubleValue(), f);
                }
                case "select" -> {
                    if (!(v instanceof String s) || !((List<?>) f.get("choices")).contains(s)) {
                        throw BridgeError.invalid(key + " must be one of the advertised choices");
                    }
                }
                case "identifier", "item" -> {
                    if (!(v instanceof String s) || !Args.IDENT.matcher(s).matches()) {
                        throw BridgeError.invalid(key + " must be an identifier ([A-Za-z0-9_.:/-], up to 128)");
                    }
                }
                case "target" -> Args.uuid(v, key);
                case "text" -> checkText(key, v);
                default -> throw BridgeError.invalid("unsupported field kind for " + key);
            }
            out.put(key, v);
        }
        return out;
    }

    private static void checkText(String key, Object v) {
        if (!(v instanceof String s) || s.length() > MAX_TEXT) {
            throw BridgeError.invalid(key + " must be text up to " + MAX_TEXT + " chars");
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) {
                throw BridgeError.invalid(key + " contains control characters");
            }
        }
        if (s.startsWith("@") || s.startsWith("/")) {
            throw BridgeError.invalid(key + " must not start with '@' or '/'");
        }
    }

    private static void checkRange(String key, double v, Map<String, Object> f) {
        if (f.get("min") instanceof Number n && v < n.doubleValue()) {
            throw BridgeError.invalid(key + " is below " + n);
        }
        if (f.get("max") instanceof Number n && v > n.doubleValue()) {
            throw BridgeError.invalid(key + " is above " + n);
        }
    }

    /** The {@code expect} precondition; its container and slot must agree with the action's own args. */
    static Expect parseExpect(Args m, Map<String, Object> actionArgs) {
        String container = Args.container(m.get("container"), "expect.container");
        int slot = Args.slot(m.get("slot"), "expect.slot");
        String id = null;
        if (m.has("id")) {
            if (!(m.get("id") instanceof String s) || !Args.IDENT.matcher(s).matches()) {
                throw BridgeError.invalid("expect.id must be an item id");
            }
            id = s;
        }
        Long count = null;
        if (m.has("count")) {
            if (!(m.get("count") instanceof Long c) || c < 0) {
                throw BridgeError.invalid("expect.count must be a non-negative integer");
            }
            count = c;
        }
        String fp = null;
        if (m.has("fingerprint")) {
            if (!(m.get("fingerprint") instanceof String s) || !Args.FINGERPRINT.matcher(s).matches()) {
                throw BridgeError.invalid("expect.fingerprint must be 64 lowercase hex characters");
            }
            fp = s;
        }
        if (id == null && count == null && fp == null) {
            throw BridgeError.invalid("expect needs at least one of id, count, fingerprint");
        }
        Object ac = actionArgs.get("container");
        if (ac != null && !ac.equals(container)) {
            throw BridgeError.invalid("args.container must equal expect.container");
        }
        Object as = actionArgs.get("slot");
        if (as != null && Args.slot(as, "args.slot") != slot) {
            throw BridgeError.invalid("args.slot must equal expect.slot");
        }
        return new Expect(container, slot, id, count, fp);
    }
}

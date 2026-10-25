package com.playermanager.hytale.api;

import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Expect;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One action.run action: its id, the capability that must be verified for it to be advertised, its ActionSpec and
 * its implementation, kept together. The body runs on a bridge thread and decides itself which part (if any) has to
 * hop onto a world's thread ({@link Call#onPlayer}); {@link Actions} wraps every body in the one error mapping.
 */
record GameAction(String id, String capability, Supplier<Map<String, Object>> spec, Body body) {
    /** The implementation: returns the ActionResult map ({@link #ok}, {@link #refused}) or throws a BridgeError. */
    interface Body {
        Map<String, Object> run(Call call);
    }

    static Map<String, Object> ok() {
        return ok(null);
    }

    static Map<String, Object> ok(Map<String, Object> result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        if (result != null) {
            m.put("result", result);
        }
        return m;
    }

    static Map<String, Object> refused(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("message", message);
        return m;
    }

    /** Parses a player id once; the dispatcher already checked the format, so this only guards direct callers. */
    static UUID parseUuid(String s, String what) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw BridgeError.invalid(what + " must be a player uuid");
        }
    }

    /** What an action body receives: the validated request plus the shared target / expect plumbing. */
    static final class Call {
        private final String id;
        private final UUID uuid;
        private final Map<String, Object> args;
        private final Expect expect;
        private final Deadline deadline;

        private Call(String id, UUID uuid, Map<String, Object> args, Expect expect, Deadline deadline) {
            this.id = id;
            this.uuid = uuid;
            this.args = args;
            this.expect = expect;
            this.deadline = deadline;
        }

        /** {@code target} is null for actions without one (whitelist_on/off). */
        static Call of(String target, Map<String, Object> args, Expect expect, Deadline deadline) {
            return new Call(target, target == null ? null : parseUuid(target, "target"), args, expect, deadline);
        }

        /** The target player id as the dispatcher sent it. */
        String id() {
            if (id == null) {
                throw BridgeError.invalid("target is required");
            }
            return id;
        }

        UUID uuid() {
            if (uuid == null) {
                throw BridgeError.invalid("target is required");
            }
            return uuid;
        }

        Map<String, Object> args() {
            return args;
        }

        Deadline deadline() {
            return deadline;
        }

        /** A non-blank text arg, or {@code fallback}. */
        String text(String key, String fallback) {
            return args.get(key) instanceof String s && !s.isBlank() ? s : fallback;
        }

        /** A text arg, or null when absent. */
        String optText(String key) {
            return args.get(key) instanceof String s ? s : null;
        }

        /**
         * For actions that do not run on a world thread: still honour {@code expect} on the owning world. This is the
         * only world hop they make, and only when the panel sent an expect.
         */
        void preflight() {
            if (expect != null) {
                onPlayer((st, ref, loc) -> ok());
            }
        }

        /** Runs {@code task} in the target's world task: {@code expect} is checked first, atomically with the task. */
        Map<String, Object> onPlayer(Worlds.PlayerTask<Map<String, Object>> task) {
            return Worlds.onPlayer(id(), Worlds.Mode.ACTION, deadline, (st, ref, loc) -> {
                if (expect != null) {
                    Containers.expect(st, ref, expect);
                }
                return task.run(st, ref, loc);
            });
        }

        /** Like {@link #onPlayer} for a player the body already located (it needed the world before queueing). */
        Map<String, Object> onLocated(Worlds.Located loc, Worlds.PlayerTask<Map<String, Object>> task) {
            return Worlds.onLocated(loc, Worlds.Mode.ACTION, deadline, (st, ref, self) -> {
                if (expect != null) {
                    Containers.expect(st, ref, expect);
                }
                return task.run(st, ref, self);
            });
        }
    }
}

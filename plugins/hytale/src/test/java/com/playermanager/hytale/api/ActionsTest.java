package com.playermanager.hytale.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.game.BridgeError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The action registry without a world: spec ids and order against the capabilities golden, the argument checks that
 * run before any world is touched, and the one error mapping (with a fake body).
 */
class ActionsTest {
    private static final String ME = "00000000-0000-0000-0000-00000000000a";
    private static final String OTHER = "00000000-0000-0000-0000-00000000000b";
    private final List<String> failures = new ArrayList<>();

    private static BridgeError error(Runnable r) {
        return assertThrows(BridgeError.class, r::run);
    }

    private BridgeError run(String action, String target, Map<String, Object> args) {
        return error(() -> Actions.run(action, target, args, null, (m, t) -> failures.add(m)));
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- registry ----------------------------------------------------------------------------

    @Test
    void specIdsAndOrderMatchTheCapabilitiesGolden() throws Exception {
        Path golden = Path.of("src/test/resources/golden/capabilities-everything-registered.json");
        List<String> expected = new ArrayList<>();
        Matcher m = Pattern.compile("^ {6}\"id\": \"([a-z_]+)\"", Pattern.MULTILINE).matcher(Files.readString(golden));
        while (m.find()) {
            expected.add(m.group(1));
        }
        assertEquals(15, expected.size());
        assertEquals(expected, Actions.ALL.stream().map(GameAction::id).toList());
        for (GameAction a : Actions.ALL) {
            assertEquals(a.id(), a.spec().get().get("id"), a.id());
            assertSame(a, Actions.find(a.id()));
        }
        assertEquals(15, new HashSet<>(expected).size(), "ids are unique");
    }

    @Test
    void theCatalogAdvertisesExactlyTheRegistrySpecsOfVerifiedCapabilities() {
        Set<String> all = new HashSet<>();
        Actions.ALL.forEach(a -> all.add(a.capability()));
        List<String> advertised = CapabilityCatalog.build("b", new Verified(all)).actions().stream()
                .map(a -> (String) a.get("id")).toList();
        assertEquals(Actions.ALL.stream().map(GameAction::id).toList(), advertised);
        Set<String> onlyKick = Set.of(Verified.KICK, Verified.HEAL);
        assertEquals(List.of("kick", "heal"), CapabilityCatalog.build("b", new Verified(onlyKick)).actions().stream()
                .map(a -> (String) a.get("id")).toList());
    }

    @Test
    void everyActionCapabilityIsAProbeSection() {
        Set<String> probes = Probe.load(getClass().getClassLoader()).capabilities();
        for (GameAction a : Actions.ALL) {
            assertTrue(probes.contains(a.capability()), a.id() + " -> " + a.capability());
        }
    }

    // ---- validation that needs no world ---------------------------------------------------------

    @Test
    void panelAliasAndUnknownActionsAreUnsupported() {
        assertEquals("unsupported", run("teleport_player", ME, args()).code());
        assertEquals("unsupported", run("explode", ME, args()).code());
    }

    @Test
    void aBadOrMissingTargetIsInvalidArgsForEveryTargetedAction() {
        for (GameAction a : Actions.ALL) {
            if (!Boolean.TRUE.equals(a.spec().get().get("target"))) {
                continue;
            }
            assertEquals("invalid_args", run(a.id(), "not-a-uuid", args()).code(), a.id());
            assertEquals("invalid_args", run(a.id(), null, args()).code(), a.id() + " without target");
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    @Test
    void gamemodeNeedsAKnownMode() {
        assertEquals("invalid_args", run("gamemode", ME, args()).code());
        assertEquals("invalid_args", run("gamemode", ME, args("mode", 3L)).code());
        assertEquals("invalid_args", run("gamemode", ME, args("mode", "godlike")).code());
    }

    @Test
    void giveNeedsAnItemAndALongAmount() {
        assertEquals("invalid_args", run("give_item", ME, args()).code());
        assertEquals("invalid_args", run("give_item", ME, args("item", "Rock_Gem_Diamond")).code());
        assertEquals("invalid_args", run("give_item", ME, args("item", "Rock_Gem_Diamond", "amount", 5)).code());
    }

    @Test
    void clearRejectsAnUnknownContainerBeforeTouchingTheWorld() {
        assertEquals("invalid_args", run("clear_inventory", ME, args("container", "Bogus")).code());
    }

    @Test
    void teleportNeedsExactlyOneCompleteForm() {
        assertEquals("invalid_args", run("teleport", ME, args()).code());
        assertEquals("invalid_args", run("teleport", ME, args("to_player", OTHER, "x", 1.0)).code());
        assertEquals("invalid_args", run("teleport", ME, args("x", 1.0, "y", 2.0)).code());
        assertEquals("invalid_args", run("teleport", ME, args("yaw", 90.0)).code());
        assertEquals("invalid_args", run("teleport", ME, args("to_player", ME)).code());
        assertEquals("invalid_args", run("teleport", ME, args("to_player", "nope")).code());
    }

    // ---- error mapping ---------------------------------------------------------------------------

    private GameAction fake(GameAction.Body body) {
        return new GameAction("fake", Verified.KICK, () -> Map.of("id", "fake"), body);
    }

    private Map<String, Object> runFake(GameAction.Body body) {
        return Actions.run(fake(body), GameAction.Call.of(null, Map.of(), null, Deadline.action()),
                (m, t) -> failures.add(m + " / " + t));
    }

    @Test
    void aBodyResultPassesThrough() {
        assertEquals(Map.of("ok", true), runFake(c -> GameAction.ok()));
        assertEquals(Map.of("ok", false, "message", "no"), runFake(c -> GameAction.refused("no")));
        assertTrue(failures.isEmpty());
    }

    @Test
    void aBridgeErrorPassesThroughUnchangedAndUnlogged() {
        BridgeError e = BridgeError.notFound("gone");
        assertSame(e, error(() -> runFake(c -> {
            throw e;
        })));
        assertTrue(failures.isEmpty());
    }

    @Test
    void aLinkageErrorIsUnsupported() {
        BridgeError e = error(() -> runFake(c -> {
            throw new NoSuchMethodError("Thing.gone()");
        }));
        assertEquals("unsupported", e.code());
        assertTrue(e.getMessage().contains("Thing.gone()"), e.getMessage());
        assertTrue(failures.isEmpty());
    }

    @Test
    void anyOtherRuntimeExceptionIsInternalAndItsCauseIsLogged() {
        BridgeError e = error(() -> runFake(c -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals("internal", e.code());
        assertEquals("action failed: IllegalStateException", e.getMessage());
        assertEquals(List.of("action fake failed / java.lang.IllegalStateException: boom"), failures);
    }
}

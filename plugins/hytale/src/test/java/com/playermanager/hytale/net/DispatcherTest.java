package com.playermanager.hytale.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playermanager.hytale.BridgeConfig;
import com.playermanager.hytale.BridgeLog;
import com.playermanager.hytale.game.BridgeError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DispatcherTest {
    static final String ID = FakeGame.ID;

    static FakeGame lastGame;

    static Dispatcher dispatcher(BridgeConfig cfg) {
        FakeGame g = new FakeGame();
        lastGame = g;
        return new Dispatcher(g, new StreamHub(g, new BridgeLog(java.nio.file.Path.of("/dev/null"), null)), cfg, "1.0.0");
    }

    static BridgeConfig defaults() {
        return new BridgeConfig(List.of("*"), List.of(), 250, 8, 25590);
    }

    static Map<String, Object> handle(Dispatcher d, String op, Map<String, Object> args) {
        return d.handle(op, args, null).data();
    }

    @Test
    void helloAdvertisesOpsActionsAndNeverTeleportPlayer() {
        Map<String, Object> data = handle(dispatcher(defaults()), "hello", Map.of("panel_version", "1.0.0"));
        Map<?, ?> caps = (Map<?, ?>) data.get("capabilities");
        List<?> ops = (List<?>) caps.get("ops");
        for (String op : List.of("server.info", "players.list", "player.get", "player.inventory", "item.get",
                "player.nearby", "positions.subscribe", "positions.keyframe", "positions.unsubscribe",
                "events.subscribe", "events.unsubscribe", "action.run")) {
            assertTrue(ops.contains(op), op);
        }
        assertFalse(ops.contains("assets.get"));
        for (Object a : (List<?>) caps.get("actions")) {
            assertFalse("teleport_player".equals(((Map<?, ?>) a).get("id")));
        }
        assertEquals("hytale", data.get("game"));
        assertEquals("0.7.0", data.get("platform_version"));
    }

    @Test
    void helloNeedsPanelVersion() {
        assertThrows(BridgeError.class, () -> handle(dispatcher(defaults()), "hello", Map.of()));
    }

    @Test
    void disabledOpsAndActionsAreNotServed() {
        BridgeConfig cfg = new BridgeConfig(List.of("server.info", "action.run"), List.of("kick"), 250, 8, 25590);
        Dispatcher d = dispatcher(cfg);
        assertTrue(d.serves("server.info"));
        assertEquals("unsupported", code(() -> handle(d, "players.list", Map.of())));
        assertEquals("unsupported", code(() -> handle(d, "positions.subscribe", Map.of("interval_ms", 250L))));
        assertEquals("unsupported", code(() -> handle(d, "action.run", Map.of("action", "kick", "target", ID))));
    }

    @Test
    void rejectsSelectorsAndMalformedIds() {
        Dispatcher d = dispatcher(defaults());
        assertEquals("invalid_args", code(() -> handle(d, "player.get", Map.of("id", "@a"))));
        assertEquals("invalid_args", code(() -> handle(d, "player.get", Map.of("id", ID.toUpperCase()))));
        assertEquals("invalid_args", code(() -> handle(d, "player.get", Map.of("id", 5L))));
        assertEquals("invalid_args", code(() -> handle(d, "player.inventory", Map.of("id", "x"))));
    }

    @Test
    void playerGetFieldsAreValidatedAgainstCapabilities() {
        Dispatcher d = dispatcher(defaults());
        assertEquals("invalid_args", code(() -> handle(d, "player.get", Map.of("id", ID, "fields", List.of("nope")))));
        assertEquals("invalid_args", code(() -> handle(d, "player.get",
                Map.of("id", ID, "fields", List.of("game.equipment")))));
        handle(d, "player.get", Map.of("id", ID, "fields", List.of("world", "game.stats")));
        assertEquals(java.util.Set.of("world", "game.stats"), lastGame.lastFields);
    }

    @Test
    void itemGetAcceptsIntOrDecimalStringSlotAndRejectsJunk() {
        Dispatcher d = dispatcher(defaults());
        handle(d, "item.get", Map.of("id", ID, "container", "HotbarInventory", "slot", "0"));
        assertEquals(0, lastGame.lastSlot);
        assertEquals("invalid_args", code(() -> handle(d, "item.get",
                Map.of("id", ID, "container", "HotbarInventory", "slot", "hotbar:0"))));
        assertEquals("invalid_args", code(() -> handle(d, "item.get",
                Map.of("id", ID, "container", "../x", "slot", 0L))));
        assertEquals("invalid_args", code(() -> handle(d, "item.get",
                Map.of("id", ID, "container", "HotbarInventory", "slot", -1L))));
    }

    @Test
    void nearbyRadiusRange() {
        Dispatcher d = dispatcher(defaults());
        assertEquals("invalid_args", code(() -> handle(d, "player.nearby", Map.of("id", ID, "radius", 33L))));
        assertEquals("invalid_args", code(() -> handle(d, "player.nearby", Map.of("id", ID, "radius", 0.5))));
        handle(d, "player.nearby", Map.of("id", ID));
        assertEquals(16.0, lastGame.lastRadius);
    }

    @Test
    void actionValidatesChoicesRangesTextAndUnknownActions() {
        Dispatcher d = dispatcher(defaults());
        assertEquals("unsupported", code(() -> handle(d, "action.run", Map.of("action", "teleport_player"))));
        assertEquals("unsupported", code(() -> handle(d, "action.run", Map.of("action", "op", "target", ID))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "gamemode", "target", ID, "args", Map.of("mode", "survival")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "give_item", "target", ID, "args", Map.of("item", "Rock", "amount", 11L)))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "give_item", "target", ID, "args", Map.of("item", "Rock", "amount", 2.0)))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "give_item", "target", ID, "args", Map.of("item", "Rock; op", "amount", 2L)))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "give_item", "target", ID, "args", Map.of("item", "Rock", "amount", 2L,
                        "container", "ArmorInventory")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "kick", "target", ID, "args", Map.of("reason", "hi\nop evil")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "kick", "target", ID, "args", Map.of("reason", "@a")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "kick", "target", ID, "args", Map.of("reason", "/op me")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of("action", "kick"))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "teleport", "target", ID, "args", Map.of("x", 1000L, "y", 0L, "z", 0L)))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", Map.of(
                "action", "teleport", "target", ID, "args", Map.of("to_player", "@p")))));
    }

    @Test
    void expectShapeIsValidated() {
        Dispatcher d = dispatcher(defaults());
        Map<String, Object> base = Map.of("action", "clear_inventory", "target", ID,
                "args", Map.of("container", "HotbarInventory"));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", with(base,
                Map.of("container", "HotbarInventory", "slot", 0L)))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", with(base,
                Map.of("container", "StorageInventory", "slot", 0L, "id", "Rock")))));
        assertEquals("invalid_args", code(() -> handle(d, "action.run", with(base,
                Map.of("container", "HotbarInventory", "slot", 0L, "fingerprint", "abc")))));
        assertEquals("conflict", code(() -> handle(d, "action.run", with(base,
                Map.of("container", "HotbarInventory", "slot", "2", "id", "bad")))));
        assertEquals("ok", code(() -> handle(d, "action.run", with(base,
                Map.of("container", "HotbarInventory", "slot", 2L, "count", 5L)))));
    }

    @Test
    void subscribeArgsAreValidated() {
        Dispatcher d = dispatcher(defaults());
        assertEquals("invalid_args", code(() -> handle(d, "positions.subscribe", Map.of())));
        assertEquals("invalid_args", code(() -> handle(d, "events.subscribe", Map.of("types", List.of()))));
        assertEquals("unsupported", code(() -> handle(d, "events.subscribe", Map.of("types", List.of("advancement")))));
        assertEquals("invalid_args", code(() -> handle(d, "positions.unsubscribe", Map.of())));
    }

    static Map<String, Object> with(Map<String, Object> base, Map<String, Object> expect) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(base);
        m.put("expect", expect);
        return m;
    }

    static String code(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (BridgeError e) {
            return e.code();
        }
    }
}

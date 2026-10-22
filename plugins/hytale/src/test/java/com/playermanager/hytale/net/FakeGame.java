package com.playermanager.hytale.net;

import com.playermanager.hytale.game.AssetSource;
import com.playermanager.hytale.game.BridgeError;
import com.playermanager.hytale.game.Capabilities;
import com.playermanager.hytale.game.Expect;
import com.playermanager.hytale.game.GameEvent;
import com.playermanager.hytale.game.GameProvider;
import com.playermanager.hytale.game.PositionSample;
import com.playermanager.hytale.wire.Canonical;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Fake provider covering every op, used by the dispatcher, hub and loopback tests. */
final class FakeGame implements GameProvider {
    static final String ID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    static final String OTHER = "853c80ef-3c37-49fd-aa49-938b674adae6";

    volatile List<PositionSample> samples = List.of();
    volatile Consumer<GameEvent> sink = e -> { };
    final List<String> actionLog = new CopyOnWriteArrayList<>();
    volatile int sampleCalls;
    final java.util.concurrent.atomic.AtomicInteger infoCalls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger listCalls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger getCalls = new java.util.concurrent.atomic.AtomicInteger();
    volatile java.util.concurrent.CountDownLatch gate;
    volatile Consumer<String> presence = id -> { };

    @Override
    public void setPresenceListener(Consumer<String> onChange) {
        this.presence = onChange;
    }

    private void gate() {
        java.util.concurrent.CountDownLatch g = gate;
        if (g != null) {
            try {
                g.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    volatile boolean drift;
    volatile Set<String> lastFields = Set.of();
    volatile int lastSlot = -1;
    volatile double lastRadius;
    volatile List<Integer> lastPath = List.of();

    static Map<String, Object> field(String key, String kind, List<String> choices, Number min, Number max) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", key);
        f.put("kind", kind);
        f.put("choices", choices);
        f.put("min", min);
        f.put("max", max);
        return f;
    }

    static Map<String, Object> action(String id, boolean target, boolean offline, Map<String, Object>... fields) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", id);
        a.put("label", id);
        a.put("target", target);
        a.put("destructive", false);
        a.put("offline", offline);
        a.put("fields", List.of(fields));
        return a;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Capabilities capabilities() {
        List<Map<String, Object>> actions = List.of(
                action("kick", true, false, field("reason", "text", List.of(), null, null)),
                action("gamemode", true, false, field("mode", "select", List.of("adventure", "creative"), null, null)),
                action("teleport", true, false,
                        field("to_player", "target", List.of(), null, null),
                        field("world", "text", List.of(), null, null),
                        field("x", "number", List.of(), -100, 100), field("y", "number", List.of(), -100, 100),
                        field("z", "number", List.of(), -100, 100)),
                action("give_item", true, false, field("item", "item", List.of(), null, null),
                        field("amount", "integer", List.of(), 1, 10),
                        field("container", "select", List.of("HotbarInventory"), null, null)),
                action("clear_inventory", true, false,
                        field("container", "select", List.of("HotbarInventory", "StorageInventory"), null, null)),
                action("whitelist_on", false, false));
        return new Capabilities("0.7.0", "0.7.0",
                List.of("server.info", "players.list", "player.get", "player.inventory", "item.get", "player.nearby",
                        "positions.subscribe", "events.subscribe", "action.run", "assets.manifest", "assets.get"),
                List.of("world", "position", "health", "max_health", "game_mode", "game"),
                List.of("stats", "groups"),
                List.of("join", "leave", "chat", "death"), actions,
                List.of("positions.stream", "inventory.write", "sensitive", "assets"));
    }

    /** Item art; null (the default) means the build has no asset API, so the dispatcher hides assets.*. */
    volatile AssetSource assets;

    @Override
    public AssetSource assetSource() {
        return assets;
    }

    /** A solid-colour PNG of the given size. */
    static byte[] png(int w, int h, int rgb) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0xff000000 | rgb);
            }
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "png", out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** In-memory icons: Soil_Dirt (no model) and Weapon_Sword_Iron (with a model), one item without art. */
    static final class FakeAssets implements AssetSource {
        final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
        volatile List<Icon> icons;
        final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();

        FakeAssets() {
            files.put("Icons/ItemsGenerated/Soil_Dirt.png", png(64, 64, 0x7a5230));
            files.put("Icons/ItemsGenerated/Weapon_Sword_Iron.png", png(64, 64, 0xc0c0c0));
            icons = List.of(
                    new Icon("Weapon_Sword_Iron", "Items/Weapons/Sword/Iron.blockymodel",
                            "Icons/ItemsGenerated/Weapon_Sword_Iron.png", "v1"),
                    new Icon("Soil_Dirt", null, "Icons/ItemsGenerated/Soil_Dirt.png", "v1"),
                    new Icon("Ghost_Item", null, "Icons/ItemsGenerated/Missing.png", "v1"));
        }

        @Override
        public List<Icon> icons() {
            return icons;
        }

        @Override
        public byte[] read(String name) {
            reads.incrementAndGet();
            return files.get(name);
        }
    }

    @Override
    public Map<String, Object> serverInfo() {
        infoCalls.incrementAndGet();
        gate();
        return Map.of("name", "fake", "max_players", 20L, "online", 1L, "uptime", 5L, "worlds",
                List.of(Map.of("world", "default", "name", "default", "loaded", true, "players", 1L)));
    }

    @Override
    public List<Map<String, Object>> playersList(boolean o, boolean sensitive) {
        listCalls.incrementAndGet();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", ID);
        p.put("name", "Steve");
        p.put("online", true);
        p.put("world", "default");
        p.put("groups", List.of("operators"));
        p.put("admin", true);
        if (sensitive) {
            p.put("ip", "127.0.0.1");
        }
        return List.of(p);
    }

    @Override
    public Map<String, Object> playerGet(String id, Set<String> fields, boolean sensitive) {
        getCalls.incrementAndGet();
        gate();
        if (!ID.equals(id)) {
            throw BridgeError.notFound("Player is not online");
        }
        lastFields = Set.copyOf(fields);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", "Steve");
        m.put("online", true);
        boolean all = fields.isEmpty();
        if (all || fields.contains("world")) {
            m.put("world", "default");
        }
        if (all || fields.contains("position")) {
            m.put("position", Map.of("x", 12.5, "y", 64.0, "z", -3.25, "yaw", 90.0, "pitch", -10.0));
        }
        if (all || fields.contains("health")) {
            m.put("health", 18.0);
        }
        if (all || fields.contains("max_health")) {
            m.put("max_health", 20.0);
        }
        if (all || fields.contains("game_mode")) {
            m.put("game_mode", "adventure");
        }
        if (sensitive) {
            m.put("ip", "127.0.0.1");
        }
        if (all || fields.contains("game") || fields.stream().anyMatch(f -> f.startsWith("game."))) {
            m.put("game", Map.of());
        }
        return m;
    }

    static Map<String, Object> item(String id, long count) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("count", count);
        r.put("hash", Canonical.hash(Canonical.renderSubset(r)));
        r.put("fingerprint", Canonical.fingerprint(r));
        return r;
    }

    @Override
    public Map<String, Object> playerInventory(String id) {
        if (!ID.equals(id)) {
            throw BridgeError.notFound("Player is not online");
        }
        return Map.of("containers", List.of(Map.of("id", "HotbarInventory", "size", 9L,
                "slots", List.of(Map.of("slot", 0L, "item", item("Weapon_Sword_Iron", 1L))))));
    }

    @Override
    public Map<String, Object> itemGet(String id, String container, int slot, List<Integer> path) {
        lastSlot = slot;
        lastPath = List.copyOf(path);
        if (!ID.equals(id) || !container.equals("HotbarInventory") || slot != 0) {
            throw BridgeError.notFound("slot is empty");
        }
        return Map.of("item", item("Weapon_Sword_Iron", 1L));
    }

    @Override
    public Map<String, Object> playerNearby(String id, double radius, boolean blocks) {
        lastRadius = radius;
        return Map.of("look", Map.of("yaw", 1.0, "pitch", 2.0, "dx", 0.0, "dy", 0.0, "dz", 1.0),
                "entities", List.of());
    }

    @Override
    public Map<String, Object> runAction(String action, String target, Map<String, Object> args, Expect expect) {
        actionLog.add(action + ":" + target + ":" + args + (expect == null ? "" : ":expect=" + expect.slot()));
        if (expect != null && "bad".equals(expect.id())) {
            throw new BridgeError("conflict", "the item in the slot changed");
        }
        return Map.of("ok", true);
    }

    @Override
    public List<PositionSample> samplePositions() {
        sampleCalls++;
        if (drift && !samples.isEmpty()) {
            List<PositionSample> moved = new ArrayList<>();
            for (PositionSample p : samples) {
                moved.add(new PositionSample(p.id(), p.name(), p.world(), p.x() + 0.25 * sampleCalls, p.y(), p.z(),
                        p.yaw(), p.pitch(), p.dead()));
            }
            return moved;
        }
        return samples;
    }

    @Override
    public void setEventSink(Consumer<GameEvent> sink) {
        this.sink = sink;
    }
}

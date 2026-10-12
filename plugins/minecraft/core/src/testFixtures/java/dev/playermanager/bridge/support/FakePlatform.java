package dev.playermanager.bridge.support;

import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.*;
import dev.playermanager.bridge.world.BlockSlice;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** In-memory platform used by the loopback tests. All "game" work runs on a single main-thread executor. */
public class FakePlatform implements Platform {
    public static final String ALICE = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    public static final String BOB = "853c80ef-3c37-49fd-aa49-938b674adae6";

    public final ExecutorService main = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "fake-main"); t.setDaemon(true); return t; });
    public volatile long delayMs;
    public final List<PositionSample> positions = new CopyOnWriteArrayList<>();
    public final List<String> actionLog = new CopyOnWriteArrayList<>();
    public volatile List<PackSource> packs = new ArrayList<>();
    public volatile RawItem slot0 = RawItem.simple("minecraft:diamond_sword", 1);
    private final PlatformInfo info;
    private final ActionRegistry registry = new ActionRegistry();
    public volatile String lastThread;

    public FakePlatform(Path root) {
        info = PlatformInfo.builder("paper", root).platformVersion("1.21.4-test").gameVersion("1.21.4").bridgeVersion("1.0.0")
                .playerFields("position", "health", "max_health", "armor", "held_item", "game_mode", "food", "experience")
                .events("join", "leave", "death", "chat", "command", "advancement", "damage", "kick", "ban")
                .features("offline_players", "sensitive", "inventory.write")
                .integrations(Arrays.asList("luckperms")).build();
        positions.add(new PositionSample(ALICE, "Alice", "world", "minecraft:overworld", 12.5, 64, -80.25, 90f, 10f, "alive"));
        registry.register(ActionSpec.of("kick", true, true, Field.text("reason", "Reason")), req -> main("kick " + req.target + " " + req.args.get("reason")));
        registry.register(ActionSpec.of("gamemode", true, false, Field.select("mode", "Mode", "survival", "creative", "adventure", "spectator").required()),
                req -> main("gamemode " + req.args.get("mode")));
        registry.register(ActionSpec.of("teleport", true, false, Field.number("x", "X", -3e7, 3e7), Field.number("y", "Y", -2048d, 2048d),
                Field.number("z", "Z", -3e7, 3e7), Field.target("to_player", "To player"), Field.text("world", "World")), req -> {
            boolean coords = req.args.containsKey("x") || req.args.containsKey("y") || req.args.containsKey("z");
            boolean toPlayer = req.args.containsKey("to_player");
            if (coords == toPlayer) return failed(BridgeException.invalid("exactly one teleport form"));
            return main("teleport");
        });
        registry.register(ActionSpec.of("remove_item_live", true, true, Field.text("container", "Container").required(), Field.slot("slot", "Slot").required(),
                Field.text("expected_id", "Expected id"), Field.integer("expected_count", "Expected count", 0, 100000), Field.text("expected_fingerprint", "Expected fingerprint")), req -> {
            CompletableFuture<ActionResult> f = new CompletableFuture<>();
            main.execute(() -> {
                try {
                    req.expect.verify(slot0);
                    actionLog.add("removed");
                    slot0 = null;
                    f.complete(ActionResult.ok());
                } catch (Throwable t) { f.completeExceptionally(t); }
            });
            return f;
        });
        registry.register(ActionSpec.of("ban_ip", true, true, Field.text("reason", "Reason")).ipTarget(), req -> main("ban_ip " + req.target + " " + req.targetIsIp));
        registry.register(ActionSpec.of("give_item", true, false, Field.item("item", "Item").required(), Field.integer("amount", "Amount", 1, 64000)), req -> main("give " + req.args.get("item")));
        registry.register(ActionSpec.of("save", false, false), req -> main("save"));
    }

    private CompletableFuture<ActionResult> failed(Throwable t) { CompletableFuture<ActionResult> f = new CompletableFuture<>(); f.completeExceptionally(t); return f; }

    private CompletableFuture<ActionResult> main(String log) {
        return supply(() -> { actionLog.add(log); return ActionResult.ok(); });
    }

    <T> CompletableFuture<T> supply(Callable<T> c) {
        CompletableFuture<T> f = new CompletableFuture<>();
        main.execute(() -> {
            try {
                lastThread = Thread.currentThread().getName();
                if (delayMs > 0) Thread.sleep(delayMs);
                f.complete(c.call());
            } catch (Throwable t) { f.completeExceptionally(t); }
        });
        return f;
    }

    public PlatformInfo info() { return info; }
    public BridgeLog log() { return BridgeLog.STDERR; }

    public Scheduler scheduler() {
        return new Scheduler() {
            public <T> CompletableFuture<T> global(Callable<T> task) { return supply(task); }
            public <T> CompletableFuture<T> forPlayer(String id, Callable<T> task) { return supply(task); }
            public <T> CompletableFuture<T> forRegion(String w, int x, int z, Callable<T> task) { return supply(task); }
        };
    }

    @Override public ServerProvider server() {
        return d -> supply(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", "Test");
            m.put("max_players", 20);
            m.put("online", 1);
            return m;
        });
    }

    @Override public PlayerProvider players() {
        return new PlayerProvider() {
            public CompletableFuture<List<Map<String, Object>>> list(boolean off, boolean sens, Deadline d) {
                return supply(() -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("id", ALICE); a.put("name", "Alice"); a.put("online", true); a.put("ping", 42);
                    a.put("groups", Arrays.asList("operators")); a.put("admin", true); a.put("ip", "1.2.3.4");
                    rows.add(a);
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("id", BOB); b.put("name", "Bob"); b.put("online", false); b.put("groups", Arrays.asList("banned")); b.put("admin", false);
                    rows.add(b);
                    return rows;
                });
            }

            public CompletableFuture<Map<String, Object>> get(String id, Set<String> fields, boolean sens, boolean unob, Deadline d) {
                return supply(() -> {
                    if (!id.equals(ALICE)) throw BridgeException.notFound("unknown player");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id); m.put("name", "Alice"); m.put("online", true);
                    m.put("world", "world"); m.put("dimension", "minecraft:overworld");
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", 12.5); pos.put("y", 64.0); pos.put("z", -80.25); pos.put("yaw", 90.0); pos.put("pitch", 10.0);
                    m.put("position", pos);
                    m.put("armor", 0.0); m.put("game_mode", "survival"); m.put("saturation", 5.0); m.put("vehicle", null);
                    Map<String, Object> xp = new LinkedHashMap<>();
                    xp.put("level", 3); xp.put("progress", 0.5); xp.put("total", 20);
                    m.put("experience", xp);
                    m.put("health", 20.0); m.put("max_health", 20.0); m.put("food", 20);
                    m.put("held_item", slot0);
                    m.put("ip", "1.2.3.4");
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("statistics", Collections.singletonMap("minecraft:custom", Collections.singletonMap("minecraft:deaths", 3)));
                    g.put("flying", false);
                    m.put("game", g);
                    return m;
                });
            }
        };
    }

    @Override public InventoryProvider inventory() {
        return (id, d) -> supply(() -> {
            if (!id.equals(ALICE)) throw BridgeException.notFound("unknown player");
            List<Map<String, Object>> cs = new ArrayList<>();
            Map<String, Object> inv = new LinkedHashMap<>();
            inv.put("id", "inventory"); inv.put("size", 36);
            List<Object> slots = new ArrayList<>();
            if (slot0 != null) { Map<String, Object> s = new LinkedHashMap<>(); s.put("slot", 0L); s.put("item", slot0); slots.add(s); }
            inv.put("slots", slots);
            cs.add(inv);
            Map<String, Object> eq = new LinkedHashMap<>();
            eq.put("id", "equipment"); eq.put("size", 5); eq.put("slots", new ArrayList<>());
            cs.add(eq);
            Map<String, Object> ec = new LinkedHashMap<>();
            ec.put("id", "ender_chest"); ec.put("size", 27); ec.put("slots", new ArrayList<>());
            cs.add(ec);
            return cs;
        });
    }

    @Override public NearbyProvider nearby() {
        return req -> supply(() -> {
            if (!req.playerId.equals(ALICE)) throw BridgeException.notFound("unknown");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("held_item", slot0);
            Map<String, Object> look = new LinkedHashMap<>();
            look.put("yaw", 90.0); look.put("pitch", 10.0); look.put("dx", -1.0); look.put("dy", 0.0); look.put("dz", 0.0);
            m.put("look", look);
            m.put("entities", new ArrayList<>());
            m.put("target", null);
            if (req.blocks) {
                BlockSlice s = new BlockSlice(0, 60, 0, 3, 2, 3);
                for (int y = 0; y < 2; y++) for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) s.set(x, y, z, y == 0 ? "minecraft:stone" : "minecraft:air");
                Map<String, Object> b = s.toMap();
                b.put("world", "world");
                b.put("dimension", "minecraft:overworld");
                m.put("blocks", b);
            }
            return m;
        });
    }

    @Override public PositionSource positions() { return () -> supply(() -> new ArrayList<>(positions)); }

    @Override public PackSourceProvider assets() { return () -> packs; }

    @Override public ActionRegistry actions() { return registry; }
}

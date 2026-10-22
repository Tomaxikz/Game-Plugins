package dev.playermanager.bridge.net;

import static dev.playermanager.bridge.support.TestClient.data;
import static dev.playermanager.bridge.support.TestClient.errorCode;
import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.spi.BiomeInfo;
import dev.playermanager.bridge.spi.BiomeRegistry;
import dev.playermanager.bridge.spi.BlockRegistry;
import dev.playermanager.bridge.spi.BlockSink;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** biomes.list and blocks.list over the real socket: capabilities, the busy window, since_hash and argument checks. */
class RegistryLoopbackTest {
    @TempDir Path root;
    BridgeCore core;
    TestClient client;

    static final class Platform extends FakePlatform {
        final CompletableFuture<Void> blocksReady = new CompletableFuture<>();
        final boolean biomesOn, blocksOn;

        Platform(Path root, boolean biomes, boolean blocks) { super(root); this.biomesOn = biomes; this.blocksOn = blocks; }

        @Override public BiomeRegistry biomes() {
            if (!biomesOn) return null;
            return () -> Arrays.asList(new BiomeInfo("minecraft:swamp", 0.8f, 0.9f, null, 6975545, 8082228, 6388580, "swamp"),
                    new BiomeInfo("minecraft:plains", 0.8f, 0.4f, null, null, null, 4159204, "none"));
        }

        @Override public BlockRegistry blocks() {
            if (!blocksOn) return null;
            return new BlockRegistry() {
                @Override public CompletableFuture<Void> whenReady() { return blocksReady; }
                @Override public void read(BlockSink sink) {
                    sink.block("minecraft:stone", 7368816, 12, 0);
                    sink.block("minecraft:air", BlockSink.NO_COLOR, 177, 0);
                }
            };
        }
    }

    @AfterEach void tearDown() {
        if (client != null) client.close();
        if (core != null) core.stop();
    }

    TestClient connect(Platform p) throws Exception {
        core = new BridgeCore(p);
        assertTrue(core.start());
        client = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        client.hello();
        return client;
    }

    @Test @SuppressWarnings("unchecked") void opsAndFeaturesAreAdvertisedExactlyTogether() throws Exception {
        Map<String, Object> caps = (Map<String, Object>) data(connect(new Platform(root, true, true)).hello()).get("capabilities");
        List<String> ops = (List<String>) caps.get("ops"), features = (List<String>) caps.get("features");
        assertTrue(ops.contains("biomes.list") && features.contains("biomes"));
        assertTrue(ops.contains("blocks.list") && features.contains("blocks"));
        tearDown(); core = null; client = null;
        TestClient c = connect(new Platform(root, false, false));
        caps = (Map<String, Object>) data(c.hello()).get("capabilities");
        ops = (List<String>) caps.get("ops");
        features = (List<String>) caps.get("features");
        assertFalse(ops.contains("biomes.list") || ops.contains("blocks.list"));
        assertFalse(features.contains("biomes") || features.contains("blocks"));
        assertEquals("unsupported", errorCode(c.call("biomes.list", new HashMap<>())));
        assertEquals("unsupported", errorCode(c.call("blocks.list", new HashMap<>())));
    }

    @Test @SuppressWarnings("unchecked") void biomesAreSortedAndUnknownArgsAreIgnored() throws Exception {
        TestClient c = connect(new Platform(root, true, false));
        Map<String, Object> args = new HashMap<>();
        args.put("whatever", 5);
        List<Map<String, Object>> l = (List<Map<String, Object>>) data(c.call("biomes.list", args)).get("biomes");
        assertEquals(2, l.size());
        assertEquals("minecraft:plains", l.get(0).get("id"));
        assertEquals("minecraft:swamp", l.get(1).get("id"));
        assertNull(l.get(0).get("grass_color"));
        assertEquals(6975545L, l.get(1).get("foliage_color"));
        assertEquals(4159204L, l.get(0).get("water_color"));
    }

    @Test @SuppressWarnings("unchecked") void blocksAreBusyUntilTheServerHasStartedThenCachedWithSinceHash() throws Exception {
        Platform p = new Platform(root, false, true);
        TestClient c = connect(p);
        assertEquals("busy", errorCode(c.call("blocks.list", new HashMap<>())));
        p.blocksReady.complete(null);
        Map<String, Object> d = null;
        for (int i = 0; i < 100 && d == null; i++) {
            Map<String, Object> r = c.call("blocks.list", new HashMap<>());
            if (Boolean.TRUE.equals(r.get("ok"))) d = data(r); else Thread.sleep(20);
        }
        assertNotNull(d, "ready after the background build");
        List<Map<String, Object>> bs = (List<Map<String, Object>>) d.get("blocks");
        assertEquals(Arrays.asList("minecraft:air", "minecraft:stone"), Arrays.asList(bs.get(0).get("id"), bs.get(1).get("id")));
        assertNull(bs.get(0).get("map_color"));
        assertTrue(bs.get(0).containsKey("map_color"));
        assertEquals(7368816L, bs.get(1).get("map_color"));
        assertNull(d.get("next"));
        String hash = (String) d.get("hash");
        assertEquals(16, hash.length());
        Map<String, Object> args = new HashMap<>();
        args.put("since_hash", hash);
        Map<String, Object> same = data(c.call("blocks.list", args));
        assertEquals(Boolean.TRUE, same.get("unchanged"));
        assertEquals(hash, same.get("hash"));
        assertNull(same.get("blocks"));
        args.put("since_hash", "nope");
        assertNotNull(data(c.call("blocks.list", args)).get("blocks"));
        args.put("since_hash", 5);
        assertEquals("invalid_args", errorCode(c.call("blocks.list", args)));
        args.clear();
        args.put("cursor", 5);
        assertEquals("invalid_args", errorCode(c.call("blocks.list", args)));
        args.put("cursor", "bad");
        assertEquals("invalid_args", errorCode(c.call("blocks.list", args)));
    }
}

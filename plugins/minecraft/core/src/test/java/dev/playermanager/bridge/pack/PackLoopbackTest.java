package dev.playermanager.bridge.pack;

import static dev.playermanager.bridge.support.TestClient.data;
import static dev.playermanager.bridge.support.TestClient.errorCode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playermanager.bridge.BridgeCore;
import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.spi.PackSourceProvider;
import dev.playermanager.bridge.support.FakePlatform;
import dev.playermanager.bridge.support.TestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code pack.manifest} / {@code pack.get} and their capability entries on the wire. */
class PackLoopbackTest {
    @TempDir Path root;
    Path packDir;
    BridgeCore core;
    TestClient client;
    volatile PackSourceProvider provider;

    final class PackPlatform extends FakePlatform {
        PackPlatform(Path r) { super(r); }

        @Override public PackSourceProvider pack() { return provider; }
    }

    @AfterEach void tearDown() throws IOException {
        if (client != null) client.close();
        if (core != null) core.stop();
        if (packDir != null) {
            try (java.util.stream.Stream<Path> walk = Files.walk(packDir)) {
                walk.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    Path pack() throws IOException {
        packDir = Files.createTempDirectory(Files.createDirectories(Path.of("build", "pack-test")), "loop");
        return packDir;
    }

    void put(String rel, byte[] data) throws IOException {
        Path f = packDir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    void start(String configJson) throws Exception {
        if (configJson != null) {
            Files.createDirectories(root.resolve(".playermanager"));
            Files.write(root.resolve(".playermanager/config.json"), configJson.getBytes(StandardCharsets.UTF_8));
        }
        core = new BridgeCore(new PackPlatform(root));
        assertTrue(core.start());
        client = TestClient.unix(root.resolve(".playermanager/bridge.sock"));
        data(client.hello());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> caps() throws Exception {
        return (Map<String, Object>) data(client.hello()).get("capabilities");
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }

    Map<String, Object> call(String op, Map<String, Object> a) throws Exception {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> r = client.call(op, a);
            if (Boolean.TRUE.equals(r.get("ok")) || !"busy".equals(errorCode(r))) return r;
            Thread.sleep(50);
        }
        throw new AssertionError("busy");
    }

    @Test void featureAndOpsAreAdvertisedTogetherOrNotAtAll() throws Exception {
        provider = null;
        start(null);
        Map<String, Object> c = caps();
        assertFalse(((List<?>) c.get("ops")).contains("pack.manifest"));
        assertFalse(((List<?>) c.get("ops")).contains("pack.get"));
        assertFalse(((List<?>) c.get("features")).contains("pack"));
        assertEquals("unsupported", errorCode(client.call("pack.manifest", args())));
        assertEquals("unsupported", errorCode(client.call("pack.get", args("paths", List.of("assets/a/models/b.json")))));
    }

    @Test void advertisedWithBothOps() throws Exception {
        provider = () -> new ArrayList<PackSource>();
        start(null);
        Map<String, Object> c = caps();
        assertTrue(((List<?>) c.get("ops")).contains("pack.manifest"));
        assertTrue(((List<?>) c.get("ops")).contains("pack.get"));
        assertTrue(((List<?>) c.get("features")).contains("pack"));
    }

    @Test void theFeatureNeedsBothOps() throws Exception {
        provider = () -> new ArrayList<PackSource>();
        start("{\"enabled_ops\":[\"server.info\",\"pack.manifest\"]}");
        Map<String, Object> c = caps();
        assertTrue(((List<?>) c.get("ops")).contains("pack.manifest"));
        assertFalse(((List<?>) c.get("ops")).contains("pack.get"));
        assertFalse(((List<?>) c.get("features")).contains("pack"));
        assertEquals("unsupported", errorCode(client.call("pack.get", args("paths", List.of("assets/a/models/b.json")))));
    }

    @Test void manifestAndGetRoundTrip() throws Exception {
        pack();
        byte[] png = new byte[70_000];
        new Random(3).nextBytes(png);
        put("assets/examplemod/textures/item/ruby.png", new byte[] {1, 2, 3});
        put("assets/examplemod/textures/item/big.png", png);
        put("assets/examplemod/models/block/ruby_block.json", "{\"parent\":\"block/cube_all\"}".getBytes(StandardCharsets.UTF_8));
        put("assets/examplemod/sounds/s.ogg", new byte[] {4});
        PackSource src = PackSource.of(packDir);
        provider = () -> Collections.singletonList(src);
        start(null);

        Map<String, Object> m = data(call("pack.manifest", args()));
        List<Map<String, Object>> files = PackServiceTest.files(m);
        assertEquals(List.of("assets/examplemod/models/block/ruby_block.json", "assets/examplemod/textures/item/big.png", "assets/examplemod/textures/item/ruby.png"),
                PackServiceTest.paths(files));
        assertEquals("039058c6f2c0cb49", files.get(2).get("hash"));
        assertEquals(3L, files.get(2).get("size"));
        assertEquals(Hashes.sha256Hex(png).substring(0, 16), files.get(1).get("hash"));
        assertEquals(70_000L, files.get(1).get("size"));
        assertFalse(m.containsKey("next"));
        assertEquals(16, ((String) m.get("hash")).length());

        Map<String, Object> g = data(call("pack.get", args("paths", List.of("assets/examplemod/textures/item/big.png", "assets/examplemod/textures/item/nope.png",
                "assets/examplemod/textures/item/ruby.png"))));
        assertArrayEquals(png, PackServiceTest.data(g, 0));
        assertArrayEquals(new byte[] {1, 2, 3}, PackServiceTest.data(g, 1));
        assertEquals(List.of("assets/examplemod/textures/item/nope.png"), g.get("missing"));
    }

    @Test void aFrameFullOfFilesCrossesTheSocketIntact() throws Exception {
        pack();
        byte[] a = new byte[PackService.MAX_FILE_BYTES], b = new byte[512 * 1024], c = new byte[100];
        new Random(4).nextBytes(a);
        new Random(5).nextBytes(b);
        new Random(6).nextBytes(c);
        put("assets/x/textures/a.png", a);
        put("assets/x/textures/b.png", b);
        put("assets/x/textures/c.png", c);
        PackSource src = PackSource.of(packDir);
        provider = () -> Collections.singletonList(src);
        start(null);
        Map<String, Object> g = data(call("pack.get", args("paths", List.of("assets/x/textures/a.png", "assets/x/textures/b.png", "assets/x/textures/c.png"))));
        assertArrayEquals(a, PackServiceTest.data(g, 0));
        assertTrue(PackServiceTest.files(g).size() >= 1);
        int served = PackServiceTest.files(g).size();
        int deferred = g.containsKey("deferred") ? ((List<?>) g.get("deferred")).size() : 0;
        assertEquals(3, served + deferred);
        assertTrue(a.length + b.length + c.length < Framing.MAX_FRAME || deferred > 0);
    }

    @Test void badArgumentsAreInvalidArgs() throws Exception {
        pack();
        put("assets/x/models/a.json", "{}".getBytes());
        PackSource src = PackSource.of(packDir);
        provider = () -> Collections.singletonList(src);
        start(null);
        assertEquals("invalid_args", errorCode(call("pack.get", args())));
        assertEquals("invalid_args", errorCode(call("pack.get", args("paths", "assets/x/models/a.json"))));
        assertEquals("invalid_args", errorCode(call("pack.get", args("paths", List.of()))));
        assertEquals("invalid_args", errorCode(call("pack.get", args("paths", List.of(1, 2)))));
        assertEquals("invalid_args", errorCode(call("pack.get", args("paths", List.of("assets/x/models/../a.json")))));
        assertEquals("invalid_args", errorCode(call("pack.get", args("paths", List.of("assets/x/lang/en_us.json")))));
        assertEquals("invalid_args", errorCode(call("pack.manifest", args("cursor", 5))));
        assertEquals("invalid_args", errorCode(call("pack.manifest", args("cursor", "../../etc/passwd"))));
        assertEquals("invalid_args", errorCode(call("pack.manifest", args("cursor", ""))));
        assertTrue(Boolean.TRUE.equals(call("pack.manifest", args("cursor", "assets/x/models/a.json")).get("ok")));
    }

    @Test void busyWhileTheFirstIndexIsBuilt() throws Exception {
        pack();
        put("assets/x/models/a.json", "{}".getBytes());
        PackServiceTest.Probe probe = new PackServiceTest.Probe(packDir);
        probe.gate = new CountDownLatch(1);
        provider = () -> Collections.<PackSource>singletonList(probe);
        start(null);
        assertEquals("busy", errorCode(client.call("pack.manifest", args())));
        assertEquals("busy", errorCode(client.call("pack.get", args("paths", List.of("assets/x/models/a.json")))));
        probe.gate.countDown();
        assertEquals(1, PackServiceTest.files(data(call("pack.manifest", args()))).size());
    }
}

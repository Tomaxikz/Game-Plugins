package dev.playermanager.bridge.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.playermanager.bridge.assets.PackSource;
import dev.playermanager.bridge.codec.Hashes;
import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.net.Framing;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.BridgeLog;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackServiceTest {
    Path tmp;
    final List<PackSource> sources = Collections.synchronizedList(new ArrayList<>());
    PackService svc;

    /** Test files live under the module's build folder: /tmp is RAM and some tests write megabytes. */
    @BeforeEach void setUp() throws IOException { tmp = Files.createTempDirectory(Files.createDirectories(Path.of("build", "pack-test")), "t"); }

    @AfterEach void tearDown() throws IOException {
        if (svc != null) svc.close();
        try (java.util.stream.Stream<Path> walk = Files.walk(tmp)) {
            walk.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    PackService service(int pageFiles, long waitMillis) {
        svc = new PackService(() -> new ArrayList<>(sources), BridgeLog.STDERR, pageFiles, waitMillis);
        return svc;
    }

    PackService service() { return service(PackService.PAGE_FILES, 10_000); }

    static void put(Path dir, String rel, byte[] data) throws IOException {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }

    static void put(Path dir, String rel, String text) throws IOException { put(dir, rel, text.getBytes(StandardCharsets.UTF_8)); }

    Path dir(String name) throws IOException { return Files.createDirectories(tmp.resolve(name)); }

    static Path zip(Path file, String prefix, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(prefix + e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return file;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> files(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("files"); }

    static List<String> paths(List<Map<String, Object>> files) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : files) out.add((String) f.get("path"));
        return out;
    }

    /** Every page of the listing, following {@code next}. */
    List<Map<String, Object>> listing() {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        String hash = null;
        do {
            Map<String, Object> page = svc.manifest(cursor);
            if (hash == null) hash = (String) page.get("hash");
            assertEquals(hash, page.get("hash"), "the listing hash is the same on every page");
            all.addAll(files(page));
            cursor = (String) page.get("next");
        } while (cursor != null);
        return all;
    }

    @SuppressWarnings("unchecked")
    static byte[] data(Map<String, Object> got, int i) { return (byte[]) ((List<Map<String, Object>>) got.get("files")).get(i).get("data"); }

    static String sha16(byte[] b) { return Hashes.sha256Hex(b).substring(0, 16); }

    static BridgeException expect(ErrorCode code, Runnable r) {
        BridgeException e = assertThrows(BridgeException.class, r::run);
        assertEquals(code, e.code());
        return e;
    }

    // ------------------------------------------------------------------ test vectors

    @Test void fileHashVectorAndEmptyListing() throws Exception {
        Path a = dir("a");
        put(a, "assets/examplemod/textures/item/ruby.png", new byte[] {1, 2, 3});
        sources.add(PackSource.of(a));
        Map<String, Object> page = service().manifest(null);
        assertEquals(Hashes.manifestHash(Collections.singletonList(new String[] {"assets/examplemod/textures/item/ruby.png", "039058c6f2c0cb49"})), page.get("hash"));
        Map<String, Object> f = files(page).get(0);
        assertEquals("assets/examplemod/textures/item/ruby.png", f.get("path"));
        assertEquals("039058c6f2c0cb49", f.get("hash"));
        assertEquals(3L, f.get("size"));
        assertNull(page.get("next"));
    }

    @Test void emptyEffectivePack() {
        Map<String, Object> page = service().manifest(null);
        assertEquals("9e076ceaf246b600", page.get("hash"));
        assertTrue(files(page).isEmpty());
        assertFalse(page.containsKey("next"));
        Map<String, Object> got = svc.get(List.of("assets/a/models/x.json"));
        assertTrue(files(got).isEmpty());
        assertEquals(List.of("assets/a/models/x.json"), got.get("missing"));
    }

    static PackFile file(String path, String hex) { return new PackFile(path, 0, 0, Long.parseUnsignedLong(hex, 16), null); }

    @Test void listingHashVectors() {
        assertEquals("9e076ceaf246b600", PackIndex.listingHash(new PackFile[0]));
        PackFile[] two = {
            file("assets/examplemod/models/block/ruby_block.json", "0123456789abcdef"),
            file("assets/examplemod/textures/item/ruby.png", "039058c6f2c0cb49"),
        };
        assertEquals("d664ed1c29d33d64", PackIndex.listingHash(two));
        assertEquals("d664ed1c29d33d64", new PackIndex(new PackFile[] {two[1], two[0]}, "k").hash, "the index sorts before hashing");
    }

    @Test void streamedListingHashEqualsTheCanonicalMessagePack() {
        for (int n : new int[] {1, 15, 16, 17, 300, 65535, 65536, 70000}) {
            PackFile[] fs = new PackFile[n];
            List<String[]> kh = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String path = String.format("assets/ns%d/textures/%s.png", i % 7, "x".repeat(i % 40) + i);
                fs[i] = new PackFile(path, 1, 1, (long) i * 0x9E3779B97F4A7C15L, null);
                kh.add(new String[] {path, PackFile.hex(fs[i].hash)});
            }
            assertEquals(Hashes.manifestHash(kh), PackIndex.listingHash(fs), "n=" + n);
        }
        String longPath = "assets/ns/models/" + "a".repeat(512 - 17 - 5) + ".json";
        PackFile[] one = {file(longPath, "00000000000000ff")};
        assertEquals(Hashes.manifestHash(Collections.singletonList(new String[] {longPath, "00000000000000ff"})), PackIndex.listingHash(one));
    }

    // ------------------------------------------------------------------ what is listed

    @Test void onlyPathsOfTheRuleAreListedOrServed() throws Exception {
        Path a = dir("a");
        String[] listed = {
            "assets/examplemod/blockstates/ruby.json", "assets/examplemod/models/block/ruby.json", "assets/examplemod/textures/block/ruby.png",
            "assets/examplemod/textures/block/ruby.png.mcmeta", "assets/minecraft/textures/block/stone.png",
        };
        String[] hidden = {
            "assets/examplemod/sounds/boom.ogg", "assets/examplemod/sounds.json", "assets/examplemod/lang/en_us.json", "assets/examplemod/items/ruby.json",
            "assets/examplemod/font/default.json", "assets/examplemod/shaders/core/x.json", "assets/examplemod/atlases/blocks.json",
            "assets/examplemod/models/Block/Up.json", "assets/examplemod/textures/block/Big.png", "assets/examplemod/textures/block/a.PNG",
            "assets/examplemod/textures/block/a.png.bak", "assets/examplemod/models/block/a.png", "assets/ExampleMod/models/a.json", "assets/examplemod/models/a b.json",
            "pack.mcmeta", "pack.png", "data/examplemod/recipe/a.json", "foo.json", "assets/examplemod/textures/block/ä.png",
        };
        for (String p : listed) put(a, p, "x");
        for (String p : hidden) put(a, p, "x");
        sources.add(PackSource.of(a));
        service();
        List<String> got = paths(listing());
        List<String> want = new ArrayList<>(Arrays.asList(listed));
        Collections.sort(want);
        assertEquals(want, got);
        Map<String, Object> served = svc.get(Arrays.asList(hidden[0].replace("sounds/boom.ogg", "textures/boom.png"), listed[0]));
        assertEquals(1, files(served).size());
        assertEquals(List.of("assets/examplemod/textures/boom.png"), served.get("missing"));
        for (String h : hidden) {
            if (!PackPaths.valid(h)) expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of(h)));
        }
    }

    @Test void listingIsSortedBytewiseOnePathOnce() throws Exception {
        Path a = dir("a"), b = dir("b");
        String[] names = {"assets/a/models/b/c.json", "assets/a/models/b.json", "assets/a-b/models/x.json", "assets/a.b/models/x.json", "assets/a/models/b-c.json",
                "assets/a/models/b_c.json", "assets/a/models/b0.json", "assets/a/blockstates/z.json", "assets/a/textures/a.png"};
        for (String p : names) put(a, p, "low " + p);
        for (String p : names) if (p.length() % 2 == 0) put(b, p, "high " + p);
        sources.add(PackSource.of(a));
        sources.add(PackSource.of(b));
        service();
        List<String> got = paths(listing());
        List<String> want = new ArrayList<>(Arrays.asList(names));
        want.sort((x, y) -> Arrays.compare(x.getBytes(StandardCharsets.UTF_8), y.getBytes(StandardCharsets.UTF_8)));
        assertEquals(want, got);
        assertEquals(new HashSet<>(got).size(), got.size());
    }

    // ------------------------------------------------------------------ precedence

    @Test void laterSourcesReplaceEarlierOnesAsWholeFiles() throws Exception {
        Path mods = dir("mods"), plugin = dir("plugin"), server = dir("server");
        put(mods, "assets/ex/models/block/a.json", "{\"from\":\"mod\",\"keep\":1}");
        put(mods, "assets/ex/models/block/only_mod.json", "{\"mod\":1}");
        put(mods, "assets/ex/textures/block/t.png", "mod-png");
        put(plugin, "assets/ex/models/block/a.json", "{\"from\":\"plugin\"}");
        put(plugin, "assets/ex/models/block/only_plugin.json", "{\"plugin\":1}");
        put(plugin, "assets/ex/textures/block/t.png", "plugin-png");
        Path zip = zip(tmp.resolve("sent.zip"), "", new LinkedHashMap<String, byte[]>() {{
            put("pack.mcmeta", "{}".getBytes());
            put("assets/ex/textures/block/t.png", "zip-png".getBytes());
            put("assets/minecraft/textures/block/stone.png", "zip-stone".getBytes());
        }});
        sources.add(PackSource.of(mods));
        sources.add(PackSource.of(plugin));
        sources.add(PackSource.of(zip));
        service();
        Map<String, Object> got = svc.get(List.of("assets/ex/models/block/a.json", "assets/ex/models/block/only_mod.json", "assets/ex/models/block/only_plugin.json",
                "assets/ex/textures/block/t.png", "assets/minecraft/textures/block/stone.png"));
        assertEquals("{\"from\":\"plugin\"}", new String(data(got, 0), StandardCharsets.UTF_8), "no JSON merging: the plugin file replaces the mod file");
        assertEquals("{\"mod\":1}", new String(data(got, 1), StandardCharsets.UTF_8));
        assertEquals("{\"plugin\":1}", new String(data(got, 2), StandardCharsets.UTF_8));
        assertEquals("zip-png", new String(data(got, 3), StandardCharsets.UTF_8));
        assertEquals("zip-stone", new String(data(got, 4), StandardCharsets.UTF_8));
        assertEquals(5, listing().size());
        Map<String, Object> entry = null;
        for (Map<String, Object> f : listing()) if (f.get("path").equals("assets/ex/textures/block/t.png")) entry = f;
        assertEquals(sha16("zip-png".getBytes()), entry.get("hash"), "the manifest hash is the winning file's hash");
        assertEquals(7L, entry.get("size"));
    }

    @Test void zipWithAWrappingFolderAndZipFileSystemRoots() throws Exception {
        Path z = zip(tmp.resolve("wrapped.zip"), "MyPack/", new LinkedHashMap<String, byte[]>() {{
            put("pack.mcmeta", "{}".getBytes());
            put("assets/w/models/item/a.json", "{}".getBytes());
            put("assets/w/sounds/s.ogg", "x".getBytes());
        }});
        sources.add(PackSource.of(z));
        // a loader-owned root: a path inside a zip file system (how a mod jar or a jar-in-jar file is read in place)
        Path jar = zip(tmp.resolve("mod.jar"), "", new LinkedHashMap<String, byte[]>() {{
            put("assets/m/textures/block/b.png", new byte[] {9, 8, 7});
            put("assets/m/lang/en_us.json", "{}".getBytes());
            put("META-INF/jars/inner.jar", "not read".getBytes());
        }});
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + jar.toUri()), Collections.<String, Object>emptyMap())) {
            sources.add(new PackSource.Dir(fs.getPath("/"), "mod:m", "mod:m:1"));
            service();
            assertEquals(List.of("assets/m/textures/block/b.png", "assets/w/models/item/a.json"), paths(listing()));
            assertArrayEquals(new byte[] {9, 8, 7}, data(svc.get(List.of("assets/m/textures/block/b.png")), 0));
        }
    }

    // ------------------------------------------------------------------ paging

    @Test void pagesFollowTheCursorAndKeepOneHash() throws Exception {
        Path a = dir("a");
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String p = String.format("assets/ex/textures/block/t%02d.png", i);
            put(a, p, new byte[] {(byte) i});
            all.add(p);
        }
        sources.add(PackSource.of(a));
        service(3, 10_000);
        Map<String, Object> p1 = svc.manifest(null);
        assertEquals(all.subList(0, 3), paths(files(p1)));
        assertEquals(all.get(2), p1.get("next"));
        Map<String, Object> p2 = svc.manifest((String) p1.get("next"));
        assertEquals(all.subList(3, 6), paths(files(p2)));
        assertEquals(p1.get("hash"), p2.get("hash"));
        assertEquals(all, paths(listing()));
        Map<String, Object> last = svc.manifest("assets/ex/textures/block/t08.png");
        assertEquals(all.subList(9, 11), paths(files(last)));
        assertFalse(last.containsKey("next"));
        Map<String, Object> beyond = svc.manifest("assets/zz/textures/a.png");
        assertTrue(files(beyond).isEmpty());
        assertFalse(beyond.containsKey("next"));
        // a cursor that is not in the listing continues after its position
        assertEquals(all.subList(5, 8), paths(files(svc.manifest("assets/ex/textures/block/t04a.png"))));
        for (String bad : new String[] {"", "junk", "assets/ex/../models/a.json", "assets/ex/sounds/a.ogg", "/assets/ex/models/a.json"}) {
            expect(ErrorCode.INVALID_ARGS, () -> svc.manifest(bad));
        }
    }

    @Test void aPageStaysWithinTheFrameBudget() throws Exception {
        Path a = dir("a");
        String deep = "d/".repeat(200);
        for (int i = 0; i < 6000; i++) put(a, "assets/ex/models/" + deep.substring(0, 300) + String.format("m%05d.json", i), "{}");
        sources.add(PackSource.of(a));
        service();
        int pages = 0, total = 0;
        String cursor = null;
        do {
            Map<String, Object> page = svc.manifest(cursor);
            assertTrue(MsgPack.encode(page).length < Framing.MAX_FRAME);
            total += files(page).size();
            cursor = (String) page.get("next");
            pages++;
        } while (cursor != null);
        assertEquals(6000, total);
        assertTrue(pages > 1, "paths this long do not fit one page");
    }

    // ------------------------------------------------------------------ pack.get

    @Test void getReturnsExactBytesInRequestOrderAndCountsDuplicatesOnce() throws Exception {
        Path a = dir("a");
        byte[] png = new byte[5000];
        new Random(7).nextBytes(png);
        byte[] json = "{\n  \"parent\": \"block/cube_all\",\n  \"x\":   1\r\n}".getBytes(StandardCharsets.UTF_8);
        put(a, "assets/ex/textures/block/z.png", png);
        put(a, "assets/ex/models/block/a.json", json);
        put(a, "assets/ex/textures/block/empty.png", new byte[0]);
        sources.add(PackSource.of(a));
        service();
        List<String> ask = List.of("assets/ex/textures/block/z.png", "assets/ex/models/nope.json", "assets/ex/models/block/a.json", "assets/ex/textures/block/z.png",
                "assets/ex/textures/block/empty.png");
        Map<String, Object> got = svc.get(ask);
        assertEquals(List.of("assets/ex/textures/block/z.png", "assets/ex/models/block/a.json", "assets/ex/textures/block/empty.png"), paths(files(got)));
        assertArrayEquals(png, data(got, 0));
        assertArrayEquals(json, data(got, 1));
        assertEquals(0, data(got, 2).length);
        assertEquals(List.of("assets/ex/models/nope.json"), got.get("missing"));
        assertFalse(got.containsKey("deferred"));
        assertFalse(got.containsKey("too_large"));
        for (Map<String, Object> f : listing()) {
            if (f.get("path").equals("assets/ex/textures/block/z.png")) assertEquals(sha16(png), f.get("hash"), "data hashes to the manifest hash");
        }
    }

    @Test void getRejectsBadRequestsBeforeServingAnything() throws Exception {
        Path a = dir("a");
        put(a, "assets/ex/models/a.json", "{}");
        sources.add(PackSource.of(a));
        service();
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(null));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of()));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 65; i++) many.add("assets/ex/models/m" + i + ".json");
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(many));
        assertEquals(64, ((List<?>) svc.get(many.subList(0, 64)).get("missing")).size());
        List<String> dup = new ArrayList<>(Collections.nCopies(65, "assets/ex/models/a.json"));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(dup));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of("assets/ex/models/a.json", "assets/ex/models/../../x.json")));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of("assets/ex/models/a.json", "assets/ex/sounds/a.ogg")));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of("/etc/passwd")));
    }

    @SuppressWarnings("unchecked")
    @Test void getAccountsForEveryPathAndStopsAtTheFirstFileThatDoesNotFit() throws Exception {
        Path a = dir("a");
        Random r = new Random(1);
        byte[] big1 = new byte[2 * 1024 * 1024], big2 = new byte[2 * 1024 * 1024], huge = new byte[PackService.MAX_FILE_BYTES + 1], exact = new byte[PackService.MAX_FILE_BYTES];
        r.nextBytes(big1); r.nextBytes(big2); r.nextBytes(huge); r.nextBytes(exact);
        put(a, "assets/ex/textures/big1.png", big1);
        put(a, "assets/ex/textures/big2.png", big2);
        put(a, "assets/ex/textures/huge.png", huge);
        put(a, "assets/ex/textures/exact.png", exact);
        put(a, "assets/ex/textures/small.png", new byte[] {1});
        sources.add(PackSource.of(a));
        service();
        String big1p = "assets/ex/textures/big1.png", big2p = "assets/ex/textures/big2.png", hugeP = "assets/ex/textures/huge.png", exactP = "assets/ex/textures/exact.png",
                smallP = "assets/ex/textures/small.png", nope = "assets/ex/textures/nope.png";
        // 2 MiB + 2 MiB do not fit one frame: big2 and everything after it that exists is deferred, even the small file
        Map<String, Object> got = svc.get(List.of(big1p, big2p, hugeP, smallP, nope));
        assertEquals(List.of(big1p), paths(files(got)));
        assertArrayEquals(big1, data(got, 0));
        assertEquals(List.of(big2p, smallP), got.get("deferred"));
        assertEquals(List.of(hugeP), got.get("too_large"));
        assertEquals(List.of(nope), got.get("missing"));
        assertTrue(MsgPack.encode(wire(got)).length <= Framing.MAX_FRAME);
        // asking again for the deferred paths serves them
        Map<String, Object> second = svc.get((List<String>) got.get("deferred"));
        assertEquals(List.of(big2p, smallP), paths(files(second)));
        assertArrayEquals(big2, data(second, 0));
        // a file of exactly 3 MiB is served when it comes first
        Map<String, Object> ex = svc.get(List.of(exactP, smallP));
        assertEquals(List.of(exactP, smallP), paths(files(ex)));
        assertArrayEquals(exact, data(ex, 0));
        assertTrue(MsgPack.encode(wire(ex)).length <= Framing.MAX_FRAME);
    }

    /** {@code prefix} plus {@code body} chars of {@code c} in folders of at most 100 chars (file names are limited to 255 bytes). */
    static String longPath(String prefix, int body, char c) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int left = body; left > 0; ) {
            int n = Math.min(left, 100);
            sb.append(String.valueOf(c).repeat(n));
            left -= n;
            if (left > 0) { sb.append('/'); left--; }
        }
        return sb.toString();
    }

    static Map<String, Object> wire(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", 2147483647);
        m.put("ok", true);
        m.put("data", data);
        return m;
    }

    @Test void theFirstFileThatFitsIsServedEvenWithSixtyFourLongPaths() throws Exception {
        Path a = dir("a");
        byte[] exact = new byte[PackService.MAX_FILE_BYTES];
        new Random(2).nextBytes(exact);
        String first = longPath("assets/ex/textures/", 512 - 19 - 4, 'f') + ".png";
        put(a, first, exact);
        sources.add(PackSource.of(a));
        service();
        List<String> ask = new ArrayList<>();
        ask.add(first);
        for (int i = 1; i < 64; i++) ask.add(longPath("assets/ex/textures/", 480, (char) ('a' + i % 26)) + i + ".png");
        Map<String, Object> got = svc.get(ask);
        assertEquals(List.of(first), paths(files(got)));
        assertEquals(63, ((List<?>) got.get("missing")).size());
        assertTrue(MsgPack.encode(wire(got)).length <= Framing.MAX_FRAME);
    }

    // ------------------------------------------------------------------ background build, busy, caching

    /** Counts reads and can hold the walk back. */
    static final class Probe extends PackSource {
        final PackSource in;
        final AtomicInteger opens = new AtomicInteger(), walks = new AtomicInteger();
        volatile CountDownLatch gate;

        Probe(Path dir) { this.in = PackSource.of(dir); }

        public String label() { return in.label(); }
        public List<String> list() throws IOException { return in.list(); }
        public byte[] read(String path) throws IOException { return in.read(path); }
        public String fingerprint() { return in.fingerprint(); }

        public void walk(Visitor v) throws IOException {
            walks.incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try { g.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.InterruptedIOException(); }
            }
            in.walk(v);
        }

        public InputStream open(String path) throws IOException {
            opens.incrementAndGet();
            return in.open(path);
        }
    }

    @Test void firstIndexAnswersBusyUntilItIsBuiltThenCachesEverything() throws Exception {
        Path a = dir("a");
        put(a, "assets/ex/models/a.json", "{}");
        Probe probe = new Probe(a);
        probe.gate = new CountDownLatch(1);
        sources.add(probe);
        service(PackService.PAGE_FILES, 30);
        expect(ErrorCode.BUSY, () -> svc.manifest(null));
        expect(ErrorCode.BUSY, () -> svc.get(List.of("assets/ex/models/a.json")));
        expect(ErrorCode.INVALID_ARGS, () -> svc.get(List.of("nope")));          // argument errors win over busy
        probe.gate.countDown();
        Map<String, Object> page = retry(() -> svc.manifest(null));
        assertEquals(1, files(page).size());
        assertEquals(1, probe.walks.get(), "one walk served all those requests");
        assertEquals(1, probe.opens.get(), "one read per file");
        for (int i = 0; i < 20; i++) svc.manifest(null);
        assertEquals(1, probe.walks.get());
    }

    <T> T retry(java.util.concurrent.Callable<T> c) throws Exception {
        for (int i = 0; i < 400; i++) {
            try {
                return c.call();
            } catch (BridgeException e) {
                if (e.code() != ErrorCode.BUSY) throw e;
                Thread.sleep(25);
            }
        }
        throw new AssertionError("still busy");
    }

    @Test void aChangeReindexesInTheBackgroundAndOnlyReadsChangedFiles() throws Exception {
        Path a = dir("a");
        for (int i = 0; i < 20; i++) put(a, String.format("assets/ex/textures/block/t%02d.png", i), new byte[] {(byte) i, 1});
        Probe probe = new Probe(a);
        sources.add(probe);
        service(PackService.PAGE_FILES, 250);
        String before = (String) retry(() -> svc.manifest(null)).get("hash");
        assertEquals(20, probe.opens.get());
        Thread.sleep(1100);                                                   // the source list is re-checked at most once per second
        put(a, "assets/ex/textures/block/t07.png", new byte[] {7, 7, 7});     // size changed
        put(a, "assets/ex/textures/block/new.png", new byte[] {5});
        Files.delete(a.resolve("assets/ex/textures/block/t00.png"));
        probe.gate = new CountDownLatch(1);
        expect(ErrorCode.BUSY, () -> svc.manifest(null));                     // re-indexing: the old listing is not served
        expect(ErrorCode.BUSY, () -> svc.get(List.of("assets/ex/textures/block/t01.png")));
        probe.gate.countDown();
        Map<String, Object> after = retry(() -> svc.manifest(null));
        assertFalse(before.equals(after.get("hash")));
        assertEquals(20, files(after).size());
        assertEquals(22, probe.opens.get(), "only the edited and the new file were read again");
        assertArrayEquals(new byte[] {7, 7, 7}, data(svc.get(List.of("assets/ex/textures/block/t07.png")), 0));
        assertEquals(List.of("assets/ex/textures/block/t00.png"), svc.get(List.of("assets/ex/textures/block/t00.png")).get("missing"));
    }

    @Test void aNewSourceAChangedPrecedenceAndAGoneSourceChangeTheListing() throws Exception {
        Path a = dir("a"), b = dir("b");
        put(a, "assets/ex/models/a.json", "low");
        put(b, "assets/ex/models/a.json", "high");
        put(b, "assets/ex/models/b.json", "b");
        PackSource sa = PackSource.of(a), sb = PackSource.of(b);
        sources.add(sa);
        service(PackService.PAGE_FILES, 5_000);
        String h1 = (String) svc.manifest(null).get("hash");
        sources.add(sb);
        Thread.sleep(1100);
        String h2 = (String) svc.manifest(null).get("hash");
        assertFalse(h1.equals(h2));
        assertEquals("high", new String(data(svc.get(List.of("assets/ex/models/a.json")), 0)));
        sources.clear();
        sources.add(sb);
        sources.add(sa);
        Thread.sleep(1100);
        assertEquals("low", new String(data(svc.get(List.of("assets/ex/models/a.json")), 0)));
        sources.remove(sa);
        Thread.sleep(1100);
        assertEquals(List.of("assets/ex/models/a.json", "assets/ex/models/b.json"), paths(listing()));
        sources.clear();
        Thread.sleep(1100);
        assertEquals("9e076ceaf246b600", svc.manifest(null).get("hash"));
    }

    @Test void filesChangedAfterIndexingAreNeverServedUnderTheOldHash() throws Exception {
        Path a = dir("a");
        put(a, "assets/ex/textures/x.png", new byte[] {1, 2, 3});
        sources.add(PackSource.of(a));
        service(PackService.PAGE_FILES, 5_000);
        String p = "assets/ex/textures/x.png";
        assertArrayEquals(new byte[] {1, 2, 3}, data(svc.get(List.of(p)), 0));
        put(a, "assets/ex/textures/x.png", new byte[] {9, 9, 9});             // same size, within the one-second window
        expect(ErrorCode.BUSY, () -> svc.get(List.of(p)));
        Map<String, Object> got = retry(() -> svc.get(List.of(p)));
        assertArrayEquals(new byte[] {9, 9, 9}, data(got, 0));
        assertEquals(sha16(new byte[] {9, 9, 9}), files(svc.manifest(null)).get(0).get("hash"));
        Files.delete(a.resolve(p));                                            // gone: busy once, then missing
        expect(ErrorCode.BUSY, () -> svc.get(List.of(p)));
        assertEquals(List.of(p), retry(() -> svc.get(List.of(p))).get("missing"));
    }

    @Test void unreadableSourcesAreSkipped() throws Exception {
        Path a = dir("a");
        put(a, "assets/ex/models/a.json", "{}");
        Path bad = tmp.resolve("broken.zip");
        Files.write(bad, "this is not a zip".getBytes());
        sources.add(PackSource.of(bad));
        sources.add(PackSource.of(tmp.resolve("missing.zip")));
        sources.add(PackSource.of(a));
        service();
        assertEquals(List.of("assets/ex/models/a.json"), paths(listing()));
    }

    @Test void concurrentFirstRequestsShareOneBuild() throws Exception {
        Path a = dir("a");
        for (int i = 0; i < 50; i++) put(a, String.format("assets/ex/models/m%02d.json", i), "{" + i + "}");
        Probe probe = new Probe(a);
        sources.add(probe);
        service(PackService.PAGE_FILES, 10_000);
        List<Thread> ts = new ArrayList<>();
        List<String> hashes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> hashes.add((String) svc.manifest(null).get("hash")));
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) t.join();
        assertEquals(8, hashes.size());
        assertEquals(1, new HashSet<>(hashes).size());
        assertEquals(1, probe.walks.get());
    }

    @Test void aStoppedServiceRefuses() {
        service();
        svc.close();
        expect(ErrorCode.UNSUPPORTED, () -> svc.manifest(null));
    }

    @Test void serviceDigestsMatchMessageDigest() throws Exception {
        Path a = dir("a");
        byte[] big = new byte[300_000];
        new Random(5).nextBytes(big);
        put(a, "assets/ex/textures/big.png", big);
        sources.add(PackSource.of(a));
        service();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String want = Hashes.hex(md.digest(big)).substring(0, 16);
        assertEquals(want, files(svc.manifest(null)).get(0).get("hash"));
        assertEquals((long) big.length, files(svc.manifest(null)).get(0).get("size"));
    }
}

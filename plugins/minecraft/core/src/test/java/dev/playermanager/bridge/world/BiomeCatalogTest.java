package dev.playermanager.bridge.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.proto.ErrorCode;
import dev.playermanager.bridge.spi.BiomeInfo;
import dev.playermanager.bridge.spi.BiomeRegistry;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Deadline;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BiomeCatalogTest {
    static BiomeInfo plains() { return new BiomeInfo("minecraft:plains", 0.8f, 0.4f, null, null, null, 0xFF3F76E4, "none"); }

    static BiomeInfo swamp() { return new BiomeInfo("minecraft:swamp", 0.8f, 0.9f, null, 6975545, 8082228, 6388580, "swamp"); }

    static final class Registry implements BiomeRegistry {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final List<BiomeInfo> biomes;
        Registry(BiomeInfo... b) { biomes = new ArrayList<>(Arrays.asList(b)); }
        @Override public CompletableFuture<Void> whenReady() { return ready; }
        @Override public List<BiomeInfo> read() { return biomes; }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(BiomeCatalog c) {
        return (List<Map<String, Object>>) ((Map<String, Object>) MsgPack.decode(c.list(Deadline.in(1000)).bytes())).get("biomes");
    }

    @Test void sortedByIdWithExactFieldsAndNullOverrides() {
        Registry r = new Registry(swamp(), new BiomeInfo("mymod:zz", 0.5f, 0.5f, 0x123456, null, null, 0, "dark_forest"), plains());
        BiomeCatalog c = new BiomeCatalog(r, BridgeLog.STDERR);
        c.start(Runnable::run);
        r.ready.complete(null);
        List<Map<String, Object>> l = list(c);
        assertEquals(Arrays.asList("minecraft:plains", "minecraft:swamp", "mymod:zz"), Arrays.asList(l.get(0).get("id"), l.get(1).get("id"), l.get(2).get("id")));
        Map<String, Object> p = l.get(0);
        assertEquals(Arrays.asList("id", "temperature", "downfall", "grass_color", "foliage_color", "dry_foliage_color", "water_color", "grass_color_modifier"), new ArrayList<>(p.keySet()));
        assertEquals(0.8, p.get("temperature"));
        assertEquals(0.4, p.get("downfall"));
        assertNull(p.get("grass_color"));
        assertNull(p.get("foliage_color"));
        assertNull(p.get("dry_foliage_color"));
        assertEquals(0x3F76E4L, p.get("water_color"), "the alpha byte is dropped");
        assertEquals("none", p.get("grass_color_modifier"));
        Map<String, Object> s = l.get(1);
        assertEquals(6975545L, s.get("foliage_color"));
        assertEquals(8082228L, s.get("dry_foliage_color"));
        assertEquals("swamp", s.get("grass_color_modifier"));
        assertEquals(0x123456L, l.get(2).get("grass_color"));
    }

    @Test void floatsAreSentAsTheDecimalTheDatapackStates() {
        BiomeInfo b = new BiomeInfo("m:b", 0.7f, -0.5f, null, null, null, 0, "none");
        assertEquals(0.7, b.temperature);
        assertEquals(-0.5, b.downfall);
        assertEquals(0.95, new BiomeInfo("m:b", 0.95f, 0f, null, null, null, 0, "none").temperature);
        assertEquals(2.0, new BiomeInfo("m:b", 2f, 0f, null, null, null, 0, "none").temperature);
    }

    @Test void alphaByteIsDroppedFromEveryColour() {
        BiomeInfo b = new BiomeInfo("m:b", 0f, 0f, 0xFF112233, 0x80445566, 0xFF778899, 0xFFAABBCC, "none");
        assertEquals(0x112233, b.grassColor.intValue());
        assertEquals(0x445566, b.foliageColor.intValue());
        assertEquals(0x778899, b.dryFoliageColor.intValue());
        assertEquals(0xAABBCC, b.waterColor);
    }

    @Test void eachIdOnceAndRequestsWaitForTheRegistry() {
        Registry r = new Registry(plains(), plains());
        BiomeCatalog c = new BiomeCatalog(r, BridgeLog.STDERR);
        c.start(Runnable::run);
        assertEquals(ErrorCode.TIMEOUT, assertThrows(BridgeException.class, () -> c.list(Deadline.in(20))).code(), "registries not loaded yet: wait, then time out");
        r.ready.complete(null);
        assertEquals(1, list(c).size());
    }

    @Test void aFailedReadAnswersUnsupported() {
        BiomeRegistry bad = new BiomeRegistry() {
            @Override public List<BiomeInfo> read() { throw new IllegalStateException("no registry"); }
        };
        BiomeCatalog c = new BiomeCatalog(bad, BridgeLog.STDERR);
        c.start(Runnable::run);
        assertEquals(ErrorCode.UNSUPPORTED, assertThrows(BridgeException.class, () -> c.list(Deadline.in(1000))).code());
    }

    @Test void theSameBytesAreServedEveryTime() {
        Registry r = new Registry(plains());
        BiomeCatalog c = new BiomeCatalog(r, BridgeLog.STDERR);
        c.start(Runnable::run);
        r.ready.complete(null);
        assertSame(c.list(Deadline.in(1000)).bytes(), c.list(Deadline.in(1000)).bytes());
    }
}

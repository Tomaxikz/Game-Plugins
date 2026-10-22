package dev.playermanager.bridge.world;

import dev.playermanager.bridge.codec.MsgPack;
import dev.playermanager.bridge.codec.PreEncoded;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BiomeInfo;
import dev.playermanager.bridge.spi.BiomeRegistry;
import dev.playermanager.bridge.spi.BridgeLog;
import dev.playermanager.bridge.spi.Deadline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The {@code biomes.list} answer (operations.md 6d, T9): the registry is read once, in the background, after the server has loaded
 * its registries; the encoded answer is kept for the lifetime of the bridge. A request that arrives before that waits for it (up
 * to its deadline). Time O(B log B) once, O(1) per request.
 */
public final class BiomeCatalog {
    private final BiomeRegistry source;
    private final BridgeLog log;
    private final CompletableFuture<PreEncoded> answer = new CompletableFuture<PreEncoded>();

    public BiomeCatalog(BiomeRegistry source, BridgeLog log) { this.source = source; this.log = log; }

    /** Starts the background read once the platform says the registries are loaded. Never throws. */
    public void start(final Executor worker) {
        source.whenReady().whenComplete(new java.util.function.BiConsumer<Void, Throwable>() {
            public void accept(Void v, Throwable t) {
                if (t != null) { fail(t); return; }
                try {
                    worker.execute(new Runnable() { public void run() { read(); } });
                } catch (Throwable e) {
                    fail(e);
                }
            }
        });
    }

    private void fail(Throwable t) {
        log.error("biomes.list: the biome registry could not be read; the op answers unsupported", t);
        answer.completeExceptionally(BridgeException.unsupported("the biome registry could not be read on this server"));
    }

    private void read() {
        long t0 = System.nanoTime();
        try {
            List<BiomeInfo> biomes = source.read();
            answer.complete(new PreEncoded(null, encode(biomes)));
            log.info("biomes.list ready: " + biomes.size() + " biomes, " + (System.nanoTime() - t0) / 1_000_000L + " ms");
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The encoded {@code {biomes: [...]}}, sorted by id. */
    static byte[] encode(List<BiomeInfo> biomes) {
        List<BiomeInfo> sorted = new ArrayList<BiomeInfo>(biomes);
        Collections.sort(sorted, new Comparator<BiomeInfo>() {
            public int compare(BiomeInfo a, BiomeInfo b) { return a.id.compareTo(b.id); }
        });
        List<Object> list = new ArrayList<Object>(sorted.size());
        String previous = null;
        for (BiomeInfo b : sorted) {
            if (b.id.equals(previous)) continue;                  // each id once
            previous = b.id;
            Map<String, Object> m = new LinkedHashMap<String, Object>(10);
            m.put("id", b.id);
            m.put("temperature", b.temperature);
            m.put("downfall", b.downfall);
            m.put("grass_color", b.grassColor);
            m.put("foliage_color", b.foliageColor);
            m.put("dry_foliage_color", b.dryFoliageColor);
            m.put("water_color", b.waterColor);
            m.put("grass_color_modifier", b.grassColorModifier);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>(2);
        out.put("biomes", list);
        return MsgPack.encode(out);
    }

    /** @throws BridgeException {@code timeout} when the registry is not read yet at the deadline, {@code unsupported} when it could not be read */
    public PreEncoded list(Deadline d) {
        try {
            return answer.get(Math.max(1, d.remainingMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw BridgeException.timeout("the biome registry is not read yet");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BridgeException.timeout("interrupted");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw c instanceof BridgeException ? (BridgeException) c : BridgeException.internal(String.valueOf(c));
        }
    }
}

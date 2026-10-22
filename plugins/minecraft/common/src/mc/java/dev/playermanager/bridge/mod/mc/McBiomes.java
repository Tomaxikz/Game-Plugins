package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.spi.BiomeInfo;
import dev.playermanager.bridge.spi.BiomeRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/**
 * {@code biomes.list} for the mod loaders (minecraft.md 6b): the live biome registry
 * ({@code registryAccess().lookupOrThrow(Registries.BIOME)}). The bridge starts when the server has started, so the registry is
 * frozen already (T9) and is read once on a bridge worker thread.
 *
 * <p>{@code Biome} has a getter for the base temperature but none for {@code downfall} (the climate settings record is private), so
 * the record's {@code downfall} accessor is called reflectively; the lookup happens when the bridge starts, and if this loader
 * does not let the bridge into that member the op is not advertised rather than guessed.
 */
final class McBiomes implements BiomeRegistry {
    private final Ctx ctx;
    private final Field climateSettings;
    private final Method downfall;

    private McBiomes(Ctx ctx, Field climateSettings, Method downfall) { this.ctx = ctx; this.climateSettings = climateSettings; this.downfall = downfall; }

    /** Null when the climate settings cannot be read on this loader. */
    static McBiomes create(Ctx ctx) {
        try {
            Field f = Biome.class.getDeclaredField("climateSettings");
            f.setAccessible(true);
            Method m = f.getType().getDeclaredMethod("downfall");
            m.setAccessible(true);
            return new McBiomes(ctx, f, m);
        } catch (Throwable t) {
            ctx.log.info("biomes.list is not offered: the biome climate settings are not readable on this loader (" + t + ")");
            return null;
        }
    }

    @Override public List<BiomeInfo> read() throws Exception {
        Registry<Biome> registry = Mc.biomeRegistry(ctx.server);
        List<BiomeInfo> out = new ArrayList<BiomeInfo>();
        for (Biome biome : registry) {
            String id = Mc.biomeKey(registry, biome);
            if (id == null) continue;                                    // a biome without a registry key cannot be addressed
            out.add(Mc.biomeInfo(id, biome, ((Number) downfall.invoke(climateSettings.get(biome))).floatValue()));
        }
        return out;
    }
}

package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.BiomeInfo;
import dev.playermanager.bridge.spi.BiomeRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code biomes.list} on Paper and Folia 1.20.5+ (minecraft.md 6b): the live biome registry read through guarded NMS reflection
 * with Mojang names. Spigot and older Paper are not offered (obfuscated names; the Bukkit {@code Biome} API has no climate or
 * colour values). Every member used is resolved when the bridge starts; if one is missing on this version the op is not advertised.
 *
 * <p>Values: {@code Biome#getBaseTemperature()}, the climate settings record's {@code downfall}, and from the special effects
 * the water colour, the grass / foliage / dry foliage overrides and the grass colour modifier. Version differences (accessor names
 * {@code getWaterColor} / {@code waterColor}, the dry foliage value from 1.21.5) are handled by name; a value the version stores
 * elsewhere makes the probe fail instead of defaulting.
 */
final class BukkitBiomes implements BiomeRegistry {
    private final Ctx ctx;
    private final Field climateSettings;
    private final Method temperature, effects, downfallMethod, serializedName;
    private final Method water, foliage, dryFoliage, grass, modifier;      // dryFoliage is null before 1.21.5
    private final Object biomeKey;

    private BukkitBiomes(Ctx ctx, Field climateSettings, Method temperature, Method effects, Method downfallMethod, Method serializedName,
                         Method water, Method foliage, Method dryFoliage, Method grass, Method modifier, Object biomeKey) {
        this.ctx = ctx; this.climateSettings = climateSettings; this.temperature = temperature; this.effects = effects;
        this.downfallMethod = downfallMethod; this.serializedName = serializedName; this.water = water; this.foliage = foliage;
        this.dryFoliage = dryFoliage; this.grass = grass; this.modifier = modifier; this.biomeKey = biomeKey;
    }

    /**
     * Null when this server cannot be read exactly (not Paper-family 1.20.5+, or a member this code relies on is missing). Classes whose
     * Mojang name Paper's reflection rewriting would turn into another class ({@code Registry}, {@code RegistryAccess}) are never looked
     * up by name: the registry access and the registry are taken from live objects when the registry is read.
     */
    static BukkitBiomes create(Ctx ctx) {
        if (!Nms.mojangMapped(ctx.env)) return null;
        try {
            Class<?> biome = Class.forName("net.minecraft.world.level.biome.Biome");
            Class<?> fx = Class.forName("net.minecraft.world.level.biome.BiomeSpecialEffects");
            Class<?> modifierEnum = Class.forName("net.minecraft.world.level.biome.BiomeSpecialEffects$GrassColorModifier");
            Field cs = biome.getDeclaredField("climateSettings");
            cs.setAccessible(true);
            Method down = Nms.required(cs.getType(), "downfall");
            Method temp = Nms.required(biome, "getBaseTemperature");
            Method effectsMethod = Nms.required(biome, "getSpecialEffects");
            Method waterM = either(fx, "getWaterColor", "waterColor");
            Method foliageM = either(fx, "getFoliageColorOverride", "foliageColorOverride");
            Method grassM = either(fx, "getGrassColorOverride", "grassColorOverride");
            Method modifierM = either(fx, "getGrassColorModifier", "grassColorModifier");
            Method dryM = optionalEither(fx, "getDryFoliageColorOverride", "dryFoliageColorOverride");
            Method name = Nms.required(modifierEnum, "getSerializedName");
            Object key = Class.forName("net.minecraft.core.registries.Registries").getField("BIOME").get(null);
            if (key == null) throw new NoSuchFieldException("Registries.BIOME");
            Object access = Reflect.call(Nms.server(), "registryAccess");
            if (access == null) throw new NoSuchMethodException("MinecraftServer#registryAccess");
            if (Nms.method(access.getClass(), LOOKUPS[0], key.getClass()) == null) Nms.required(access.getClass(), LOOKUPS[1], key.getClass());
            return new BukkitBiomes(ctx, cs, temp, effectsMethod, down, name, waterM, foliageM, dryM, grassM, modifierM, key);
        } catch (Throwable t) {
            ctx.log.info("biomes.list is not offered on this server: " + t);
            return null;
        }
    }

    private static final String[] LOOKUPS = {"registryOrThrow", "lookupOrThrow"};

    /**
     * The registry of a key from the live access object. {@code registryOrThrow(ResourceKey)} (1.20.5 - 1.21.x) returns the registry; before
     * 1.21.2 {@code lookupOrThrow} returns a {@code HolderLookup} view instead, from 1.21.2 the registry. The first of the two that yields
     * an {@link Iterable} (the registry) is used.
     */
    private static Object registryOf(Object access, Object key) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String name : LOOKUPS) {
            Method m = Nms.method(access.getClass(), name, key.getClass());
            if (m == null) continue;
            Object r = m.invoke(access, key);
            if (r instanceof Iterable) return r;
            last = new NoSuchMethodException(name + " did not return an iterable registry");
        }
        throw last != null ? last : new NoSuchMethodException("RegistryAccess#registryOrThrow / #lookupOrThrow");
    }

    @Override public CompletableFuture<Void> whenReady() { return Nms.firstTick(ctx); }

    @Override public List<BiomeInfo> read() throws Exception {
        Object access = Reflect.call(Nms.server(), "registryAccess");
        if (access == null) throw new IllegalStateException("registry access unavailable");
        Object registry = registryOf(access, biomeKey);
        Method getKey = Nms.required(registry.getClass(), "getKey", Object.class);
        List<BiomeInfo> out = new ArrayList<BiomeInfo>();
        for (Object biome : (Iterable<?>) registry) {
            String id = Nms.keyOf(getKey, registry, biome);
            if (id == null) continue;                                   // a biome without a registry key cannot be addressed
            Object fx = effects.invoke(biome);
            Object climate = climateSettings.get(biome);
            Object mod = modifier.invoke(fx);
            Object modName = serializedName.invoke(mod);
            out.add(new BiomeInfo(id, ((Number) temperature.invoke(biome)).floatValue(), ((Number) downfallMethod.invoke(climate)).floatValue(),
                    optional(grass.invoke(fx)), optional(foliage.invoke(fx)), dryFoliage == null ? null : optional(dryFoliage.invoke(fx)),
                    ((Number) water.invoke(fx)).intValue(), modName != null ? modName.toString() : ((Enum<?>) mod).name().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /** Record accessor or getter, whichever this version has; throws when neither exists. */
    private static Method either(Class<?> c, String getter, String accessor) throws ReflectiveOperationException {
        Method m = Nms.method(c, getter);
        return m != null ? m : Nms.required(c, accessor);
    }

    /** Like {@link #either}, null when this version has neither (the dry foliage colour before 1.21.5). */
    private static Method optionalEither(Class<?> c, String getter, String accessor) {
        Method m = Nms.method(c, getter);
        return m != null ? m : Nms.method(c, accessor);
    }

    private static Integer optional(Object o) {
        if (o instanceof Optional) return ((Optional<?>) o).isPresent() ? Integer.valueOf(((Number) ((Optional<?>) o).get()).intValue()) : null;
        throw new IllegalStateException("unexpected colour override type " + o);
    }
}

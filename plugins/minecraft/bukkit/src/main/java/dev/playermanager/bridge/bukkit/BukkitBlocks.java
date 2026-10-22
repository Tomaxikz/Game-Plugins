package dev.playermanager.bridge.bukkit;

import dev.playermanager.bridge.spi.BlockFlags;
import dev.playermanager.bridge.spi.BlockRegistry;
import dev.playermanager.bridge.spi.BlockSink;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code blocks.list} on Paper and Folia 1.20.5+ (minecraft.md 6c): every block of {@code BuiltInRegistries.BLOCK} with the values
 * of its states, read through guarded NMS reflection with Mojang names, on a bridge worker thread (T10). Level-dependent getters
 * get {@code EmptyBlockGetter.INSTANCE} at {@code BlockPos.ZERO}; no live level or chunk is touched. Spigot and older Paper are
 * not offered. Every member is resolved when the bridge starts; a missing one means the op is not advertised.
 *
 * <p>Cost of {@link #read}: one pass over all states, each with about a dozen reflective getter calls (no allocation beyond the
 * call's argument array); the property string is built only for the states the sink wants.
 */
final class BukkitBlocks implements BlockRegistry {
    private final Ctx ctx;
    private final Object blockRegistry, emptyGetter, zero, waterlogged;
    private final Method getKey, defaultState, stateDefinition, possibleStates, defaultMapColor;
    private final Method isAir, liquid, canOcclude, fullBlock, collision, shapeEmpty, replaceable, renderShape, hasBlockEntity, light, mapColor;
    private final Method properties, hasProperty, getValue, propertyName, valueName;
    private final Field colorId, colorValue;
    private final Set<String> logged = new HashSet<String>();

    private BukkitBlocks(Ctx ctx, Object[] o, Method[] m, Field[] f) {
        this.ctx = ctx;
        blockRegistry = o[0]; emptyGetter = o[1]; zero = o[2]; waterlogged = o[3];
        getKey = m[0]; defaultState = m[1]; stateDefinition = m[2]; possibleStates = m[3]; defaultMapColor = m[4];
        isAir = m[5]; liquid = m[6]; canOcclude = m[7]; fullBlock = m[8]; collision = m[9]; shapeEmpty = m[10]; replaceable = m[11];
        renderShape = m[12]; hasBlockEntity = m[13]; light = m[14]; mapColor = m[15];
        properties = m[16]; hasProperty = m[17]; getValue = m[18]; propertyName = m[19]; valueName = m[20];
        colorId = f[0]; colorValue = f[1];
    }

    /**
     * Null when this server cannot be read exactly (not Paper-family 1.20.5+, a member of minecraft.md 6c is missing, or a sample read
     * of air and stone does not give sane values). Paper rewrites the reflective lookups of every plugin class from Spigot to Mojang
     * names, which turns a few Mojang names into other classes (observed: {@code net.minecraft.core.Registry} becomes {@code IdMap}), so the
     * registry's class is taken from the live registry object instead of by name, and the sample read catches any other mix-up.
     */
    static BukkitBlocks create(Ctx ctx) {
        if (!Nms.mojangMapped(ctx.env)) return null;
        try {
            Class<?> builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Class<?> block = Class.forName("net.minecraft.world.level.block.Block");
            Class<?> definition = Class.forName("net.minecraft.world.level.block.state.StateDefinition");
            Class<?> base = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Class<?> holder = Class.forName("net.minecraft.world.level.block.state.StateHolder");
            Class<?> property = Class.forName("net.minecraft.world.level.block.state.properties.Property");
            Class<?> getter = Class.forName("net.minecraft.world.level.BlockGetter");
            Class<?> pos = Class.forName("net.minecraft.core.BlockPos");
            Class<?> shape = Class.forName("net.minecraft.world.phys.shapes.VoxelShape");
            Class<?> color = Class.forName("net.minecraft.world.level.material.MapColor");
            Object registry = builtIn.getField("BLOCK").get(null);
            Object[] o = {
                registry,
                Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null),
                pos.getField("ZERO").get(null),
                Class.forName("net.minecraft.world.level.block.state.properties.BlockStateProperties").getField("WATERLOGGED").get(null)};
            Method[] m = {
                Nms.required(registry.getClass(), "getKey", Object.class), Nms.required(block, "defaultBlockState"), Nms.required(block, "getStateDefinition"),
                Nms.required(definition, "getPossibleStates"), Nms.required(block, "defaultMapColor"),
                Nms.required(base, "isAir"), Nms.required(base, "liquid"), Nms.required(base, "canOcclude"),
                Nms.required(base, "isCollisionShapeFullBlock", getter, pos), Nms.required(base, "getCollisionShape", getter, pos), Nms.required(shape, "isEmpty"),
                Nms.required(base, "canBeReplaced"), Nms.required(base, "getRenderShape"), Nms.required(base, "hasBlockEntity"),
                Nms.required(base, "getLightEmission"), Nms.required(base, "getMapColor", getter, pos),
                Nms.required(holder, "getProperties"), Nms.required(holder, "hasProperty", property), Nms.required(holder, "getValue", property),
                Nms.required(property, "getName"), Nms.required(property, "getName", Comparable.class)};
            Field[] f = {color.getField("id"), color.getField("col")};
            for (Object x : o) if (x == null) throw new NoSuchFieldException("registry constant");
            BukkitBlocks b = new BukkitBlocks(ctx, o, m, f);
            b.sampleRead();
            return b;
        } catch (Throwable t) {
            ctx.log.info("blocks.list is not offered on this server: " + t);
            return null;
        }
    }

    /** Reads air and stone through the real code path and checks the values every version agrees on (air is air, stone is a coloured solid block). */
    private void sampleRead() throws Exception {
        final int[][] seen = new int[2][];
        read(new BlockSink() {
            public void block(String id, int mapColor, int flags, int light) {
                if (id.equals("minecraft:air")) seen[0] = new int[] {mapColor, flags};
                else if (id.equals("minecraft:stone")) seen[1] = new int[] {mapColor, flags};
            }
            public boolean differs(int mapColor, int flags) { return false; }
            public void state(String properties, int mapColor, int flags) { }
        }, "minecraft:air", "minecraft:stone");
        if (seen[0] == null || seen[1] == null || (seen[0][1] & BlockFlags.AIR) == 0 || (seen[1][1] & BlockFlags.AIR) != 0
                || (seen[1][1] & BlockFlags.FULL_CUBE) == 0 || seen[1][0] == BlockSink.NO_COLOR) {
            throw new IllegalStateException("sample read of air and stone gave unexpected values");
        }
    }

    @Override public CompletableFuture<Void> whenReady() { return Nms.firstTick(ctx); }

    @Override public void read(BlockSink sink) throws Exception { read(sink, (String[]) null); }

    /** @param only when non-null, just these ids (the start-up sample read) */
    private void read(BlockSink sink, String... only) throws Exception {
        for (Object block : (Iterable<?>) blockRegistry) {
            String id = Nms.keyOf(getKey, blockRegistry, block);
            if (id == null) continue;                                  // a block without a registry key cannot be addressed
            if (only != null && !Arrays.asList(only).contains(id)) continue;
            Object def = defaultState.invoke(block);
            int[] v = new int[3];                                      // {map colour, flags, light}
            boolean[] failed = new boolean[1];
            values(def, block, true, v, failed);
            if (failed[0] && logged.add(id)) ctx.log.warn("blocks.list: a getter of the default state of " + id + " threw; its value is 0 (map colour: the block default)");
            sink.block(id, v[0], v[1], v[2]);
            Object[] props = sortedProperties(def);
            for (Object state : (List<?>) possibleStates.invoke(stateDefinition.invoke(block))) {
                if (state == def) continue;
                try {
                    values(state, block, false, v, failed);
                } catch (Throwable t) {
                    continue;                                          // not listed: the panel uses the block default for it
                }
                if (sink.differs(v[0], v[1])) sink.state(propertyString(state, props), v[0], v[1]);
            }
        }
    }

    /**
     * {map colour, flags, light} of one state into {@code out}. A non-default state propagates the first exception; the default
     * state ({@code lenient}) zeroes the value whose getter threw (map colour: the block's own default) and sets {@code failed}.
     */
    private void values(Object st, Object block, boolean lenient, int[] out, boolean[] failed) throws Exception {
        int flags = 0;
        int lightLevel = 0;
        int rgb = BlockSink.NO_COLOR;
        try {
            Object c = mapColor.invoke(st, emptyGetter, zero);
            rgb = rgbOf(c);
        } catch (InvocationTargetException e) {
            if (!lenient) throw e;
            failed[0] = true;
            try { rgb = rgbOf(defaultMapColor.invoke(block)); } catch (InvocationTargetException e2) { rgb = BlockSink.NO_COLOR; }
        }
        flags |= flag(isAir, st, null, BlockFlags.AIR, lenient, failed);
        flags |= flag(liquid, st, null, BlockFlags.LIQUID, lenient, failed);
        flags |= flag(canOcclude, st, null, BlockFlags.CAN_OCCLUDE, lenient, failed);
        flags |= flag(fullBlock, st, emptyGetter, BlockFlags.FULL_CUBE, lenient, failed);
        try {
            if ((Boolean) shapeEmpty.invoke(collision.invoke(st, emptyGetter, zero))) flags |= BlockFlags.NO_COLLISION;
        } catch (InvocationTargetException e) {
            if (!lenient) throw e;
            failed[0] = true;
        }
        flags |= flag(replaceable, st, null, BlockFlags.REPLACEABLE, lenient, failed);
        try {
            String shape = ((Enum<?>) renderShape.invoke(st)).name();
            if (shape.equals("INVISIBLE")) flags |= BlockFlags.RENDER_INVISIBLE;
            else if (shape.equals("ENTITYBLOCK_ANIMATED")) flags |= BlockFlags.BLOCK_ENTITY;      // gone in recent versions; found by name
        } catch (InvocationTargetException e) {
            if (!lenient) throw e;
            failed[0] = true;
        }
        flags |= flag(hasBlockEntity, st, null, BlockFlags.BLOCK_ENTITY, lenient, failed);
        try {
            lightLevel = ((Number) light.invoke(st)).intValue();
        } catch (InvocationTargetException e) {
            if (!lenient) throw e;
            failed[0] = true;
        }
        if (lightLevel > 0) flags |= BlockFlags.EMITS_LIGHT;
        if ((Boolean) hasProperty.invoke(st, waterlogged)) flags |= BlockFlags.WATERLOGGABLE;
        out[0] = rgb;
        out[1] = flags;
        out[2] = lightLevel;
    }

    private int flag(Method getter, Object st, Object arg, int bit, boolean lenient, boolean[] failed) throws Exception {
        try {
            Object r = arg == null ? getter.invoke(st) : getter.invoke(st, arg, zero);
            return ((Boolean) r) ? bit : 0;
        } catch (InvocationTargetException e) {
            if (!lenient) throw e;
            failed[0] = true;
            return 0;
        }
    }

    /** 0xRRGGBB of a {@code MapColor}, or NO_COLOR for {@code MapColor.NONE} (id 0). */
    private int rgbOf(Object mapColorObject) throws IllegalAccessException {
        if (colorId.getInt(mapColorObject) == 0) return BlockSink.NO_COLOR;
        return colorValue.getInt(mapColorObject) & 0xFFFFFF;
    }

    private static final Comparator<Object[]> BY_NAME = new Comparator<Object[]>() {
        public int compare(Object[] a, Object[] b) { return ((String) a[0]).compareTo((String) b[0]); }
    };

    /** The block's properties, sorted by name bytewise (every state of a block has the same ones), as {name, property} pairs. */
    private Object[] sortedProperties(Object state) throws Exception {
        List<Object[]> l = new ArrayList<Object[]>();
        for (Object p : (Iterable<?>) properties.invoke(state)) l.add(new Object[] {propertyName.invoke(p), p});
        Object[][] a = l.toArray(new Object[l.size()][]);
        Arrays.sort(a, BY_NAME);
        return a;
    }

    private String propertyString(Object state, Object[] sorted) throws Exception {
        if (sorted.length == 0) return "";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < sorted.length; i++) {
            Object[] np = (Object[]) sorted[i];
            if (i > 0) sb.append(',');
            sb.append((String) np[0]).append('=').append((String) valueName.invoke(np[1], getValue.invoke(state, np[1])));
        }
        return sb.toString();
    }
}

package dev.playermanager.bridge.mod.mc;

import com.mojang.serialization.DynamicOps;
import dev.playermanager.bridge.item.RawItem;
import dev.playermanager.bridge.mod.ItemWire;
import dev.playermanager.bridge.nbt.Nbt;
import dev.playermanager.bridge.proto.BridgeException;
import dev.playermanager.bridge.spi.BridgeLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * ItemStack to record and back through the registry-aware {@code ItemStack} codec (data components, 1.20.5+).
 * The game thread only produces a {@link Snap} (NBT bytes); parsing into a {@link RawItem} happens on a worker thread.
 */
final class McItems {
    /** Immutable item snapshot taken on the game thread. */
    static final class Snap {
        final String id;
        final int count;
        final byte[] nbt;
        final int maxDamage;

        Snap(String id, int count, byte[] nbt, int maxDamage) { this.id = id; this.count = count; this.nbt = nbt; this.maxDamage = maxDamage; }

        /** Off-thread conversion; falls back to the id-only record when the NBT cannot be read. */
        RawItem toRaw() {
            if (nbt != null) {
                try {
                    RawItem r = RawItem.fromNbt(Nbt.readRoot(nbt), maxDamage);
                    if (r != null) return new RawItem(r.id, count > 0 ? count : r.count, r.components, r.tag, null, maxDamage);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return new RawItem(id, count, null, null, null, maxDamage);
        }
    }

    private final MinecraftServer server;
    private final BridgeLog log;

    McItems(MinecraftServer server, BridgeLog log) { this.server = server; this.log = log; }

    private DynamicOps<Tag> ops() { return RegistryOps.create(NbtOps.INSTANCE, server.registryAccess()); }

    static boolean isEmpty(ItemStack s) { return s == null || s.isEmpty(); }

    static String idOf(ItemStack s) { return BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); }

    /** Game thread. Null for an empty stack. */
    Snap snapshot(ItemStack s) {
        if (isEmpty(s)) return null;
        String id = idOf(s);
        int max = Math.max(0, s.getMaxDamage());
        byte[] bytes = null;
        try {
            Tag t = ItemStack.CODEC.encodeStart(ops(), s).result().orElse(null);
            if (t instanceof CompoundTag) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
                DataOutputStream out = new DataOutputStream(bo);
                NbtIo.write((CompoundTag) t, out);
                bytes = bo.toByteArray();
            }
        } catch (Throwable e) {
            log.debug("item encode failed: " + e);
        }
        return new Snap(id, s.getCount(), bytes, max);
    }

    /** Game thread: full conversion (used for the {@code expect} check). */
    RawItem rawNow(ItemStack s) {
        Snap sn = snapshot(s);
        return sn == null ? null : sn.toRaw();
    }

    /** Game thread: builds a stack from an encoded item record or a bare id. */
    ItemStack fromArg(Object arg, int defaultCount) {
        ItemWire.Spec spec = ItemWire.parse(arg, defaultCount);
        if (spec.snbt != null) throw BridgeException.invalid("legacy snbt items are not supported on this Minecraft version");
        try {
            CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(spec.toNbtBytes())));
            ItemStack stack = ItemStack.CODEC.parse(ops(), tag).result().orElse(null);
            if (stack == null || stack.isEmpty()) {
                // report why: unknown item vs bad component
                String err = ItemStack.CODEC.parse(ops(), tag).error().map(e -> e.message()).orElse("item could not be built");
                throw BridgeException.invalid("cannot build item: " + trim(err));
            }
            return stack;
        } catch (BridgeException e) {
            throw e;
        } catch (Throwable t) {
            throw BridgeException.invalid("cannot build item: " + trim(String.valueOf(t.getMessage())));
        }
    }

    private static String trim(String s) { return s.length() > 200 ? s.substring(0, 200) : s; }

    /** Plain id to a stack of {@code count} (clamped to 99 by the codec). */
    ItemStack plain(String id, int count) {
        return fromArg(id, count);
    }

}

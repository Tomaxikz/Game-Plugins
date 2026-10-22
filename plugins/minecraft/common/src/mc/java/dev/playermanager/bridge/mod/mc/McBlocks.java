package dev.playermanager.bridge.mod.mc;

import dev.playermanager.bridge.spi.BlockFlags;
import dev.playermanager.bridge.spi.BlockRegistry;
import dev.playermanager.bridge.spi.BlockSink;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code blocks.list} for the mod loaders (minecraft.md 6c): every block of {@code BuiltInRegistries.BLOCK} with the values of its
 * states, read directly on a bridge worker thread (T10). The bridge starts when the server has started, so mods have registered all
 * blocks. Level-dependent getters get an empty block getter at 0,0,0 (see the version shim {@code Mc}); no live level or chunk is touched.
 *
 * <p>Cost: one pass over all states, a dozen getter calls each; a property string is built only for states the sink wants. Mod getters
 * that throw are handled per state as the contract says.
 */
final class McBlocks implements BlockRegistry {
    private final Ctx ctx;
    private final Set<String> logged = new HashSet<String>();

    McBlocks(Ctx ctx) { this.ctx = ctx; }

    private interface Check { boolean test(BlockState s); }

    @Override public void read(BlockSink sink) {
        int[] v = new int[3];                                          // {map colour, flags, light}
        boolean[] failed = new boolean[1];
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = Mc.blockId(block);
            BlockState def;
            try {
                def = block.defaultBlockState();
                failed[0] = false;
                values(def, block, true, v, failed);
            } catch (Throwable t) {
                ctx.log.warn("blocks.list: " + id + " has no readable default state (" + t + "); not listed");
                continue;
            }
            if (failed[0] && logged.add(id)) ctx.log.warn("blocks.list: a getter of the default state of " + id + " threw; its value is 0 (map colour: the block default)");
            sink.block(id, v[0], v[1], v[2]);
            try {
                for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                    if (st == def) continue;
                    try {
                        values(st, block, false, v, failed);
                    } catch (Throwable t) {
                        continue;                                      // not listed: the panel uses the block default for it
                    }
                    if (sink.differs(v[0], v[1])) sink.state(Mc.propertyString(st), v[0], v[1]);
                }
            } catch (Throwable t) {
                ctx.log.warn("blocks.list: the states of " + id + " could not be listed (" + t + ")");
            }
        }
    }

    /**
     * {map colour, flags, light} of one state into {@code out}. A non-default state propagates the first exception; the default
     * state ({@code lenient}) zeroes the value whose getter threw (map colour: the block's own default) and sets {@code failed}.
     */
    private static void values(BlockState st, Block block, boolean lenient, int[] out, boolean[] failed) {
        int rgb;
        try {
            rgb = Mc.mapColor(st);
        } catch (RuntimeException | LinkageError e) {
            if (!lenient) throw e;
            failed[0] = true;
            try { rgb = Mc.defaultMapColor(block); } catch (RuntimeException | LinkageError e2) { rgb = BlockSink.NO_COLOR; }
        }
        int light = 0;
        try {
            light = Mc.lightEmission(st);
        } catch (RuntimeException | LinkageError e) {
            if (!lenient) throw e;
            failed[0] = true;
        }
        int flags = light > 0 ? BlockFlags.EMITS_LIGHT : 0;
        flags |= flag(BlockState::isAir, st, BlockFlags.AIR, lenient, failed);
        flags |= flag(Mc::isLiquid, st, BlockFlags.LIQUID, lenient, failed);
        flags |= flag(BlockState::canOcclude, st, BlockFlags.CAN_OCCLUDE, lenient, failed);
        flags |= flag(Mc::isFullCollisionShape, st, BlockFlags.FULL_CUBE, lenient, failed);
        flags |= flag(Mc::hasNoCollision, st, BlockFlags.NO_COLLISION, lenient, failed);
        flags |= flag(Mc::isReplaceable, st, BlockFlags.REPLACEABLE, lenient, failed);
        flags |= flag(Mc::isRenderInvisible, st, BlockFlags.RENDER_INVISIBLE, lenient, failed);
        flags |= flag(Mc::hasBlockEntityOrAnimatedRender, st, BlockFlags.BLOCK_ENTITY, lenient, failed);
        flags |= flag(Mc::isWaterloggable, st, BlockFlags.WATERLOGGABLE, lenient, failed);
        out[0] = rgb;
        out[1] = flags;
        out[2] = light;
    }

    private static int flag(Check c, BlockState st, int bit, boolean lenient, boolean[] failed) {
        try {
            return c.test(st) ? bit : 0;
        } catch (RuntimeException | LinkageError e) {
            if (!lenient) throw e;
            failed[0] = true;
            return 0;
        }
    }
}

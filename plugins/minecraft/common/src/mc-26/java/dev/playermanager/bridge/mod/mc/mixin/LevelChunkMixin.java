package dev.playermanager.bridge.mod.mc.mixin;

import dev.playermanager.bridge.mod.mc.ChunkHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks a chunk dirty for {@code chunks.subscribe} whenever a block really changed. The hook is a handful of field reads while idle. */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void pm$blockChanged(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null) ChunkHooks.blockChanged((LevelChunk) (Object) this);
    }
}

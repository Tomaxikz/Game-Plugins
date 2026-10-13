package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.mc.ChunkHooks;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric without Fabric API has no chunk-load event: {@code setLoaded(true)} is what {@code ChunkStatusTasks#full} calls once a chunk is complete. */
@Mixin(LevelChunk.class)
abstract class LevelChunkLoadMixin {
    @Inject(method = "setLoaded", at = @At("RETURN"))
    private void pm$loaded(boolean loaded, CallbackInfo ci) {
        if (loaded) {
            LevelChunk self = (LevelChunk) (Object) this;
            ChunkHooks.chunkLoaded(self.getLevel(), self);
        }
    }
}

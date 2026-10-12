package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @Inject(method = "initServer", at = @At("RETURN"))
    private void pm$started(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) FabricEntry.serverStarted((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void pm$stopping(CallbackInfo ci) {
        FabricEntry.serverStopping();
    }
}

package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class CommonPacketListenerMixin {
    /** Server initiated disconnects: kicks, bans, timeouts. */
    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void pm$disconnect(DisconnectionDetails details, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b == null || !((Object) this instanceof ServerGamePacketListenerImpl)) return;
        ServerPlayer p = ((ServerGamePacketListenerImpl) (Object) this).player;
        String reason = details.reason().getString();
        b.events().disconnectReason(p.getUUID(), reason);
        b.events().kick(p, reason);
    }

    /** Client initiated disconnects (quit) and connection losses. */
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void pm$lost(DisconnectionDetails details, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b == null || !((Object) this instanceof ServerGamePacketListenerImpl)) return;
        b.events().disconnectReason(((ServerGamePacketListenerImpl) (Object) this).player.getUUID(), details.reason().getString());
    }
}

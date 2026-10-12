package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class ConfigurationPacketListenerMixin {
    @Shadow protected abstract GameProfile playerProfile();

    /** The vanilla server ignores the client brand; remember it for players.list / player.get. */
    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void pm$brand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b == null) return;
        if (packet.payload() instanceof BrandPayload) {
            b.events().brand(this.playerProfile().id(), ((BrandPayload) packet.payload()).brand());
        }
    }
}

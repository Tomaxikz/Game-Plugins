package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void pm$join(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b != null) b.events().join(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void pm$leave(ServerPlayer player, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b != null) b.events().leave(player);
    }

    @Inject(method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V", at = @At("HEAD"))
    private void pm$chat(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound chatType, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b != null) b.events().chat(sender, message.signedContent());
    }
}

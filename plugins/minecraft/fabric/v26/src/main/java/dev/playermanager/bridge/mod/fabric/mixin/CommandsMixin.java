package dev.playermanager.bridge.mod.fabric.mixin;

import com.mojang.brigadier.ParseResults;
import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void pm$command(ParseResults<CommandSourceStack> parse, String command, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b == null) return;
        CommandSourceStack src = parse.getContext().getSource();
        if (src.getEntity() instanceof ServerPlayer) b.events().command((ServerPlayer) src.getEntity(), command);
        else if ("Server".equals(src.getTextName())) b.events().command(null, command);
    }
}

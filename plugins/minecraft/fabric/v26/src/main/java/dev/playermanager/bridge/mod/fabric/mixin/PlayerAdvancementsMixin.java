package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
    @Shadow private ServerPlayer player;
    @Shadow public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder holder);
    @Unique private boolean pm$wasDone;

    @Inject(method = "award", at = @At("HEAD"))
    private void pm$before(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        this.pm$wasDone = this.getOrStartProgress(holder).isDone();
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void pm$after(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || this.pm$wasDone || !this.getOrStartProgress(holder).isDone()) return;
        McBridge b = FabricEntry.bridge();
        if (b == null) return;
        String title = holder.value().display().map(d -> d.title().getString()).orElse(null);
        b.events().advancement(this.player, holder.id().toString(), title);
    }
}

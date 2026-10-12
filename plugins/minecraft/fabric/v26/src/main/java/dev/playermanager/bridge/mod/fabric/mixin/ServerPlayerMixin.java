package dev.playermanager.bridge.mod.fabric.mixin;

import dev.playermanager.bridge.mod.fabric.FabricEntry;
import dev.playermanager.bridge.mod.mc.McBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
    @Unique private float pm$healthBefore;

    @Inject(method = "die", at = @At("HEAD"))
    private void pm$death(DamageSource source, CallbackInfo ci) {
        McBridge b = FabricEntry.bridge();
        if (b != null) b.events().death((ServerPlayer) (Object) this, source);
    }

    @Inject(method = "hurtServer", at = @At("HEAD"))
    private void pm$damageBefore(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        this.pm$healthBefore = ((ServerPlayer) (Object) this).getHealth();
    }

    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void pm$damageAfter(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        McBridge b = FabricEntry.bridge();
        if (b == null) return;
        ServerPlayer self = (ServerPlayer) (Object) this;
        float after = self.getHealth();
        float lost = this.pm$healthBefore - after;
        b.events().damage(self, source, lost > 0 ? lost : damage, after);
    }
}

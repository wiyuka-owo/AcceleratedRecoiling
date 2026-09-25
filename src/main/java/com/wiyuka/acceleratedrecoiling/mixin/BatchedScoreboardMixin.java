package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedRules;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Scoreboard.class)
public class BatchedScoreboardMixin {
    @Inject(method = "addPlayerToTeam", at = { @At("HEAD"), @At("RETURN") })
    private void ar$joined(CallbackInfoReturnable<Boolean> cir) {
        BatchedRules.invalidatePolicy();
    }

    @Inject(method = { "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            "removePlayerTeam" }, at = { @At("HEAD"), @At("RETURN") })
    private void ar$left(CallbackInfo ci) {
        BatchedRules.invalidatePolicy();
    }
}

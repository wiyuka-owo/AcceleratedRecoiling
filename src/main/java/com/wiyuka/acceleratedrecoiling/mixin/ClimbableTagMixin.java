package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Holder.Reference.class)
public abstract class ClimbableTagMixin {
    @Inject(method = "bindTags", at = @At("RETURN"))
    private void ar$tagsChanged(CallbackInfo ci) {
        BatchedRules.invalidatePolicy();
    }
}

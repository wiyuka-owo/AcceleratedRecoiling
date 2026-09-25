package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.IndexedSection;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeSection;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntitySection.class)
public class RealtimeSectionMixin implements IndexedSection {
    @Unique
    private RealtimeSection ar$index;

    @Override
    public RealtimeSection ar$realtimeSection() {
        if (ar$index == null) {
            ar$index = new RealtimeSection();
        }
        return ar$index;
    }

    @Inject(method = "add", at = @At("RETURN"))
    private void added(EntityAccess entity, CallbackInfo ci) {
        if (ar$index != null) {
            ar$index.added(entity);
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void removed(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
        if (ar$index != null && cir.getReturnValueZ()) {
            ar$index.removed(entity);
        }
    }
}

package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchDiagnostics;
import com.wiyuka.acceleratedrecoiling.natives.realtime.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.PushableCache;
import com.wiyuka.acceleratedrecoiling.natives.realtime.PushableMemoryEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PushableMemoryMixin implements PushableMemoryEntity {
    @Unique
    private boolean ar$pushableValue;

    @Unique
    private boolean ar$pushableValid;

    @Unique
    private long ar$pushableEpoch;

    @Override
    public void ar$invalidatePushable() {
        ar$pushableValid = false;
    }

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void ar$memoizedPushable(CallbackInfoReturnable<Boolean> cir) {
        long epoch = BatchedRules.epoch();
        if (ar$pushableValid && ar$pushableEpoch == epoch) {
            if (BatchDiagnostics.ENABLED) PushableCache.holds++;
            cir.setReturnValue(ar$pushableValue);
            return;
        }

        if (BatchDiagnostics.ENABLED) {
            if (ar$pushableValid) {
                PushableCache.epochMisses++;
            } else {
                PushableCache.misses++;
            }
        }

        LivingEntity self = (LivingEntity) (Object) this;
        ar$pushableValue = self.isAlive() && !self.isSpectator() && !self.onClimbable();
        ar$pushableValid = true;
        ar$pushableEpoch = epoch;
        cir.setReturnValue(ar$pushableValue);
    }
}
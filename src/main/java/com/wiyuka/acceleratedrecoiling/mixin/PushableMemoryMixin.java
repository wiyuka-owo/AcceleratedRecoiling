package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.PushableMemoryEntity;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.config.NeoForgeServerConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class PushableMemoryMixin implements PushableMemoryEntity {
    @Unique
    private boolean ar$climbableValue;

    @Unique
    private boolean ar$pushableValid;

    @Unique
    private long ar$pushableEpoch;

    @Override
    public void ar$invalidatePushable() {
        ar$pushableValid = false;
    }

    @Override
    public boolean ar$isPushableInTickingSection() {
        var entity = (LivingEntity) (Object) this;
        if (!entity.isAlive() || entity.isSpectator()) {
            return false;
        }

        long epoch = BatchedRules.epoch();
        if (ar$hasClimbableCache(epoch)) {
            return !ar$climbableValue;
        }

        return !ar$rememberClimbable(entity, entity.onClimbable(), epoch);
    }

    @WrapOperation(method = "isPushable", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;onClimbable()Z"))
    private boolean ar$memoizedClimbable(LivingEntity entity, Operation<Boolean> original) {
        if (!RealtimeNative.isEnabled() || NeoForgeServerConfig.INSTANCE.fullBoundingBoxLadders.get()
                || !BatchedRules.plain(entity.getClass())) {
            return original.call(entity);
        }

        long epoch = BatchedRules.epoch();
        if (ar$hasClimbableCache(epoch)) {
            return ar$climbableValue;
        }

        return ar$rememberClimbable(entity, original.call(entity), epoch);
    }

    @Unique
    private boolean ar$hasClimbableCache(long epoch) {
        return ar$pushableValid && ar$pushableEpoch == epoch;
    }

    @Unique
    private boolean ar$rememberClimbable(LivingEntity entity, boolean climbable, long epoch) {
        ar$climbableValue = climbable;
        ar$pushableValid = !climbable && BatchedRules.classify(entity) == BatchedRules.PUSHABLE;
        ar$pushableEpoch = epoch;
        return ar$climbableValue;
    }
}

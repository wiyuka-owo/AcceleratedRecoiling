package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.IndexedEntity;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SynchedEntityData.class)
public class BatchedSyncedStateMixin {
    @Shadow
    @Final
    private SyncedDataHolder entity;

    @Inject(method = { "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V", "assignValues" },
            at = { @At("HEAD"), @At("RETURN") })
    private void ar$dataChanged(CallbackInfo ci) {
        if (entity instanceof IndexedEntity indexed) {
            indexed.ar$collisionStateDirty();
        }
    }
}

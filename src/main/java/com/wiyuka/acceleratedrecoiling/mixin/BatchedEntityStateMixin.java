package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.IndexedEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class BatchedEntityStateMixin {
    @Inject(method = { "baseTick", "setPosRaw", "addPassenger", "removePassenger", "stopRiding", "setId", "setUUID" },
            at = { @At("HEAD"), @At("RETURN") })
    private void ar$stateChanged(CallbackInfo ci) {
        ((IndexedEntity) this).ar$collisionStateDirty();
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = { @At("HEAD"), @At("RETURN") })
    private void ar$mounted(CallbackInfoReturnable<Boolean> cir) {
        ((IndexedEntity) this).ar$collisionStateDirty();
    }

    @Inject(method = "getInBlockState", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/Entity;inBlockState:Lnet/minecraft/world/level/block/state/BlockState;", shift = At.Shift.AFTER))
    private void ar$blockCachePopulated(CallbackInfoReturnable<BlockState> cir) {
        ((IndexedEntity) this).ar$collisionStateDirty();
    }

    @Inject(method = { "baseTick", "setPosRaw" }, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/Entity;inBlockState:Lnet/minecraft/world/level/block/state/BlockState;", shift = At.Shift.AFTER))
    private void ar$blockCacheCleared(CallbackInfo ci) {
        ((IndexedEntity) this).ar$collisionStateDirty();
    }
}

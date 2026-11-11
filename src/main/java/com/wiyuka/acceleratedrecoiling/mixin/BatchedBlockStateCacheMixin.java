package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class BatchedBlockStateCacheMixin {
    @Inject(method = "getInBlockState", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/Entity;inBlockState:Lnet/minecraft/world/level/block/state/BlockState;", shift = At.Shift.AFTER))
    private void ar$blockCachePopulated(CallbackInfoReturnable<BlockState> cir) {
        ((IndexedEntity) this).ar$collisionStateDirty();
    }
}

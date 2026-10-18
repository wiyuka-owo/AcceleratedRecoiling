package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.EntityObstacles;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityGetter.class)
interface BatchedMovementMixin {
    @Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true)
    private void ar$entityObstacles(Entity source, AABB area, CallbackInfoReturnable<List<VoxelShape>> result) {
        if ((Object) this instanceof Level level) {
            var shapes = EntityObstacles.collect(level, source, area);
            if (shapes != null) {
                result.setReturnValue(shapes);
            }
        }
    }
}

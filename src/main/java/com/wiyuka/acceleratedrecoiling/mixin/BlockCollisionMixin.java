package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.BlockCollisionCache;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.ShapeIntersection;
import net.minecraft.core.Cursor3D;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockCollisions.class)
abstract class BlockCollisionMixin {
    @WrapOperation(method = "computeNext", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean ar$intersectBox(VoxelShape shape, VoxelShape box, BooleanOp operation,
            Operation<Boolean> original) {
        if (!RealtimeNative.isEnabled() || operation != BooleanOp.AND) {
            return original.call(shape, box, operation);
        }

        return switch (ShapeIntersection.test(shape, box)) {
            case ACCEPTED -> true;
            case REJECTED -> false;
            case VANILLA, CALLBACKS -> original.call(shape, box, operation);
        };
    }

    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "(IIIIII)Lnet/minecraft/core/Cursor3D;"))
    private Cursor3D ar$indexedCursor(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Operation<Cursor3D> original, CollisionGetter getter, Entity source, AABB area,
            boolean onlySuffocating) {
        var cursor = BlockCollisionCache.cursor(getter, area, onlySuffocating);
        return cursor != null ? cursor : original.call(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

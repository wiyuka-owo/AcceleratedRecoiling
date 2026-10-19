package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.ShapeIntersection;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractBoat.class)
abstract class BoatCollisionMixin {
    @WrapOperation(method = "getGroundFriction", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean ar$intersectGround(VoxelShape shape, VoxelShape box, BooleanOp operation,
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
}

package com.wiyuka.acceleratedrecoiling.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.BlockCollisionCache;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.FullBlockCollisions;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class BlockMovementMixin {
    @Inject(method = "collectColliders(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true)
    private static void ar$fullBlocks(Entity source, Level level, List<VoxelShape> entities, AABB area,
            CallbackInfoReturnable<List<VoxelShape>> result) {
        var blocks = BlockCollisionCache.collect(source, level, area);
        if (blocks == null) {
            return;
        }

        var border = level.getWorldBorder();
        boolean closeToBorder = border.isInsideCloseToBorder(source, area);
        if (entities.isEmpty() && !closeToBorder && BlockCollisionCache.fullBlockMovementEnabled()) {
            result.setReturnValue(blocks);
            return;
        }

        var colliders = ImmutableList.<VoxelShape>builderWithExpectedSize(entities.size() + 1);
        colliders.addAll(entities);
        if (closeToBorder) {
            colliders.add(border.getCollisionShape());
        }
        colliders.addAll(blocks);
        result.setReturnValue(colliders.build());
    }

    @Inject(method = "collideWithShapes", at = @At("HEAD"), cancellable = true)
    private static void ar$clipFullBlocks(Vec3 movement, AABB box, List<VoxelShape> shapes,
            CallbackInfoReturnable<Vec3> result) {
        if (shapes instanceof FullBlockCollisions blocks) {
            result.setReturnValue(blocks.collide(movement, box));
        }
    }

    @Inject(method = "collectCandidateStepUpHeights", at = @At("HEAD"), cancellable = true)
    private static void ar$fullBlockSteps(AABB box, List<VoxelShape> shapes, float maxHeight, float skippedHeight,
            CallbackInfoReturnable<float[]> result) {
        if (shapes instanceof FullBlockCollisions blocks) {
            result.setReturnValue(blocks.stepHeights(box, maxHeight, skippedHeight));
        }
    }

    @WrapOperation(method = "checkSupportingBlock",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;findSupportingBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;"))
    private Optional<BlockPos> ar$supportingBlock(Level level, Entity source, AABB area,
            Operation<Optional<BlockPos>> original) {
        var blocks = BlockCollisionCache.fullBlocks(source, level, area);
        if (blocks == null) {
            return original.call(level, source, area);
        }
        return blocks.supportingBlock(source.position());
    }
}

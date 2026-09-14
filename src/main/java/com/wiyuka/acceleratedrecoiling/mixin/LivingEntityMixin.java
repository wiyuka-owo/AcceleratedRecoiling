package com.wiyuka.acceleratedrecoiling.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.CollisionMapData;
import com.wiyuka.acceleratedrecoiling.natives.NativeInterface;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(value = LivingEntity.class, priority = 1100)
public class LivingEntityMixin {
    @Unique
    private int lastClimbableCheckTick = -1;
    @Unique
    private boolean cachedClimbableResult = false;

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;doPush(Lnet/minecraft/world/entity/Entity;)V"
            )
    )
    private void doPushVerify(LivingEntity instance, Entity entity, Operation<Void> original) {
        if (instance.getBoundingBox().intersects(entity.getBoundingBox())) original.call(instance, entity);
    }

    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void injectOnClimbableHead(CallbackInfoReturnable<Boolean> cir) {
        if (((LivingEntity) (Object) this).tickCount == this.lastClimbableCheckTick) {
            cir.setReturnValue(this.cachedClimbableResult);
        }
    }

    @Inject(method = "onClimbable", at = @At("RETURN"))
    private void injectOnClimbableReturn(CallbackInfoReturnable<Boolean> cir) {
        this.lastClimbableCheckTick = ((LivingEntity) (Object) this).tickCount;
        this.cachedClimbableResult = cir.getReturnValueZ();
    }

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;isPassenger()Z"
            )
    )
    private boolean isPassenger(Entity instance, Operation<Boolean> original) {
        if (instance.isPassenger()) {
            return true;
        }
        AABB myBox = ((LivingEntity) (Object) this).getBoundingBox();
        AABB otherBox = instance.getBoundingBox();
        if (!myBox.intersects(otherBox)) return true;
        return false;
    }

    @WrapOperation(
            method = "pushEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getPushableEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
            )
    )
    private List<Entity> replace(Level instance, Entity entity, AABB boundingBox, Operation<List<Entity>> original) {
        if (!FoldConfig.enableEntityCollision || entity instanceof Player || entity.level().isClientSide()) {
            return original.call(instance, entity, boundingBox);
        }

        if (NativeInterface.isJavaVanilla()) {
            return NativeInterface.javaVanillaNeighbors(entity, boundingBox);
        }

        ICustomData data = (ICustomData) entity;

        if (data.getDensity() < FoldConfig.densityThreshold) return original.call(instance, entity, boundingBox);

        List<Entity> rawList = CollisionMapData.getCollisionList(entity, instance);

        Predicate<? super Entity> pushablePredicate = EntitySelector.pushableBy(entity);

        List<Entity> filteredList = new ArrayList<>();
        for (Entity e : rawList) {
            if (pushablePredicate.test(e)) {
                filteredList.add(e);
            }
        }

        return filteredList;
    }
}

package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import com.wiyuka.acceleratedrecoiling.natives.ParallelAABB;
import com.wiyuka.acceleratedrecoiling.natives.TempID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Shadow
    @Final
    private EntityTickList entityTickList;

    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD")
    )
    private void tick(BooleanSupplier booleanSupplier, CallbackInfo ci) {
        TempID.tickStart();

        List<Entity> livingEntities = new ArrayList<>();
        List<Entity> queryEntities = new ArrayList<>();
        this.entityTickList.forEach(entity -> {
            if (!entity.isRemoved()) {
                queryEntities.add(entity);
                if (!(entity instanceof Player)) {
                    livingEntities.add(entity);
                }
            }
            TempID.addEntity(entity);
        });
        if (FoldConfig.enableEntityCollision) {
            ParallelAABB.handleEntityPush(livingEntities, queryEntities, 1.0E-7);
        }
    }
}

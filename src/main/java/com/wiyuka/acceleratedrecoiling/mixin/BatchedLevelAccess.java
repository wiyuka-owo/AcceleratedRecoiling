package com.wiyuka.acceleratedrecoiling.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerLevel.class)
public interface BatchedLevelAccess {

    @Invoker("getEntities")
    LevelEntityGetter<Entity> ar$entities();

    @Accessor("dragonParts")
    Int2ObjectMap<EnderDragonPart> ar$dragonParts();
}

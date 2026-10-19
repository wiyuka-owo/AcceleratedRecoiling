package com.wiyuka.acceleratedrecoiling.natives.realtime.compat;

import com.wiyuka.acceleratedrecoiling.natives.realtime.CollisionResult;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedEntity;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;

public final class BatchedRules {
    private BatchedRules() {
    }

    private static final AtomicLong POLICY = new AtomicLong();

    public static long epoch() {
        return POLICY.get();
    }

    public static void invalidatePolicy() {
        POLICY.incrementAndGet();
    }

    private static boolean vanillaEntity(Class<?> type) {
        String vanillaPackage = Entity.class.getPackageName();
        String entityPackage = type.getPackageName();
        return entityPackage.equals(vanillaPackage) || entityPackage.startsWith(vanillaPackage + ".");
    }

    private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && LivingEntity.class.isAssignableFrom(type)
                    && CollisionMethods.inherited(type, "pushable");
        }
    };

    public static boolean plain(Class<?> type) {
        return PLAIN.get(type);
    }

    private static final ClassValue<Boolean> QUERYABLE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && LivingEntity.class.isAssignableFrom(type)
                    && CollisionMethods.inherited(type, "queryable");
        }
    };

    private static final ClassValue<Boolean> SOFT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && CollisionMethods.inherited(type, "soft");
        }
    };

    public static boolean soft(Class<?> type) {
        return SOFT.get(type);
    }

    private static final ClassValue<Boolean> OBSTACLE_SOURCE = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && CollisionMethods.inherited(type, "obstacleSource");
        }
    };

    public static boolean obstacleSource(Class<?> type) {
        return OBSTACLE_SOURCE.get(type);
    }

    public static CollisionResult classify(Entity entity) {
        return classify(entity, false);
    }

    public static CollisionResult classify(Entity entity, boolean allowUncachedState) {
        if (entity.getClass() == ItemEntity.class || entity.getClass() == ExperienceOrb.class) {
            return CollisionResult.REJECTED;
        }
        boolean plain = plain(entity.getClass());
        if (!plain && !QUERYABLE.get(entity.getClass())) {
            return CollisionResult.VANILLA;
        }
        LivingEntity living = (LivingEntity) entity;
        if (!living.isAlive()) {
            return CollisionResult.REJECTED;
        }
        var team = entity.getTeam();
        if (team != null && team.getClass() != PlayerTeam.class) {
            return CollisionResult.VANILLA;
        }
        if (team != null || entity.isPassenger() || entity.isVehicle()) {
            return CollisionResult.CALLBACKS;
        }

        BlockState state = ((IndexedEntity) entity).ar$cachedBlockState();
        if (state == null) {
            if (!allowUncachedState) {
                return CollisionResult.CALLBACKS;
            }
            state = entity.level().getBlockState(entity.blockPosition());
        }
        if (state.getClass() != BlockState.class) {
            return CollisionResult.VANILLA;
        }

        if (state.is(BlockTags.CLIMBABLE) || climbableTrapdoor(entity, state)) {
            return CollisionResult.CALLBACKS;
        }

        return plain ? CollisionResult.ACCEPTED : CollisionResult.CALLBACKS;
    }

    private static boolean climbableTrapdoor(Entity entity, BlockState state) {
        if (!(state.getBlock() instanceof TrapDoorBlock) || !state.getValue(TrapDoorBlock.OPEN)) {
            return false;
        }

        BlockState below = entity.level().getBlockState(entity.blockPosition().below());
        return below.is(Blocks.LADDER)
                && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }
}

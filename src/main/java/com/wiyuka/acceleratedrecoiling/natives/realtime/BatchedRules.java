package com.wiyuka.acceleratedrecoiling.natives.realtime;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.CommonLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

public final class BatchedRules {
    public static final int VANILLA_CALLBACKS = -1;
    public static final int REJECTED = 0;
    public static final int PUSHABLE = 1;

    private BatchedRules() {
    }

    private static final AtomicLong POLICY = new AtomicLong();

    public static long epoch() {
        return POLICY.get();
    }

    public static void invalidatePolicy() {
        POLICY.incrementAndGet();
    }

    private static final ClassValue<Boolean> CLEAN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                var info = ClassInfo.forName(type.getName());
                while (info != null) {
                    for (var applied : info.getAppliedMixins()) {
                        String mixin = applied.getClassName();
                        if (!mixin.startsWith("com.wiyuka.acceleratedrecoiling.mixin.")
                                && !mixin.startsWith("com.wiyuka.acceleratedrecoiling.collisiontest.mixin.")
                                && !LithiumCompatibility.allows(mixin)) {
                            AcceleratedRecoiling.LOGGER.debug("Collision optimization disabled for {} by {}",
                                    type.getName(), mixin);
                            return false;
                        }
                    }

                    if (info.getSuperName() == null) {
                        return true;
                    }
                    info = info.getSuperClass();
                }

                throw new IllegalStateException("Missing class metadata");
            } catch (RuntimeException | LinkageError e) {
                AcceleratedRecoiling.LOGGER.warn("Could not check collision compatibility for {}; using vanilla collisions",
                        type.getName(), e);
                return false;
            }
        }
    };

    private static boolean vanillaEntity(Class<?> type) {
        String vanillaPackage = Entity.class.getPackageName();
        String entityPackage = type.getPackageName();
        return entityPackage.equals(vanillaPackage) || entityPackage.startsWith(vanillaPackage + ".");
    }

    private static final ClassValue<Boolean> PLAIN = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && LivingEntity.class.isAssignableFrom(type)
                    && CLEAN.get(type) && CollisionMethods.inherited(type, "pushable");
        }
    };

    private static final ClassValue<Boolean> PLAIN_BLOCK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return CLEAN.get(type) && CollisionMethods.inherited(type, "ladder");
        }
    };

    public static boolean plain(Class<?> type) {
        return PLAIN.get(type);
    }

    public static boolean orderedSections() {
        return CLEAN.get(EntitySection.class)
                && CLEAN.get(ClassInstanceMultiMap.class);
    }

    private static final ClassValue<Boolean> SOFT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return vanillaEntity(type) && CLEAN.get(type) && CollisionMethods.inherited(type, "soft");
        }
    };

    public static boolean soft(Class<?> type) {
        return SOFT.get(type);
    }

    public static boolean cleanWorld() {
        return CLEAN.get(Level.class) && CLEAN.get(ServerLevel.class) && CLEAN.get(EntitySelector.class)
                && CLEAN.get(EntitySectionStorage.class) && CLEAN.get(BlockState.class)
                && CLEAN.get(Holder.Reference.class) && CLEAN.get(Scoreboard.class)
                && CLEAN.get(ServerScoreboard.class) && CLEAN.get(SynchedEntityData.class)
                && CLEAN.get(IBlockExtension.class) && CLEAN.get(CommonHooks.class) && CLEAN.get(EntityGetter.class)
                && CLEAN.get(CommonLevelAccessor.class);
    }

    public static int classify(Entity entity) {
        if (!plain(entity.getClass())) {
            return VANILLA_CALLBACKS;
        }
        LivingEntity living = (LivingEntity) entity;
        if (!living.isAlive()) {
            return REJECTED;
        }
        if (entity.getTeam() != null || entity.isPassenger() || entity.isVehicle()) {
            return VANILLA_CALLBACKS;
        }

        BlockState state = ((IndexedEntity) entity).ar$cachedBlockState();
        if (BatchDiagnostics.ENABLED && state == null) {
            BatchDiagnostics.coldStates++;
        }
        if (state == null || state.getClass() != BlockState.class || !PLAIN_BLOCK.get(state.getBlock().getClass())) {
            return VANILLA_CALLBACKS;
        }

        return state.is(BlockTags.CLIMBABLE) ? VANILLA_CALLBACKS : PUSHABLE;
    }
}

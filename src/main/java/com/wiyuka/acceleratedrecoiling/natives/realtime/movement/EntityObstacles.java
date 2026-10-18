package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import com.google.common.collect.ImmutableList;
import com.wiyuka.acceleratedrecoiling.mixin.BatchedLevelAccess;
import com.wiyuka.acceleratedrecoiling.mixin.RealtimeGetterAccess;
import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedSection;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class EntityObstacles {
    private EntityObstacles() {
    }

    @SuppressWarnings("unchecked")
    public static List<VoxelShape> collect(Level level, Entity source, AABB area) {
        if (!RealtimeNative.isEnabled() || level.getClass() != ServerLevel.class
                || !level.getServer().isSameThread()
                || source != null && !BatchedRules.obstacleSource(source.getClass())) {
            return null;
        }

        var access = (BatchedLevelAccess) level;
        if (!access.ar$dragonParts().isEmpty()) {
            return null;
        }
        var getter = access.ar$entities();
        if (getter.getClass() != LevelEntityGetterAdapter.class) {
            return null;
        }

        if (area.getSize() < 1.0E-7) {
            return List.of();
        }

        var storage = ((RealtimeGetterAccess<Entity>) getter).ar$sectionStorage();
        var bounds = area.inflate(1.0E-7);
        var collisions = new ArrayList<Entity>();
        level.getProfiler().incrementCounter("getEntities");

        storage.forEachAccessibleNonEmptySection(bounds, section -> {
            var index = ((IndexedSection) section).ar$realtimeSection();
            for (Entity other : index.obstacles(section)) {
                if (other == source || !other.getBoundingBox().intersects(bounds) || other.isSpectator()) {
                    continue;
                }
                boolean collides = source == null ? other.canBeCollidedWith() : source.canCollideWith(other);
                if (collides) {
                    collisions.add(other);
                }
            }
            return Continuation.CONTINUE;
        });

        var shapes = ImmutableList.<VoxelShape>builderWithExpectedSize(collisions.size());
        for (Entity entity : collisions) {
            shapes.add(Shapes.create(entity.getBoundingBox()));
        }
        return shapes.build();
    }
}

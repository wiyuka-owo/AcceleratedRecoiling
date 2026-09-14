package com.wiyuka.acceleratedrecoiling.natives;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

public class CollisionMapData {
    private static final Int2ObjectOpenHashMap<IntArrayList> collisionMap = new Int2ObjectOpenHashMap<>(10000);

    public static void putCollision(int idA, int idB) {
        addSingle(idA, idB);
        addSingle(idB, idA);
    }

    private static void addSingle(int source, int target) {
        IntArrayList list = collisionMap.get(source);
        if (list == null) {
            list = new IntArrayList();
            collisionMap.put(source, list);
        }
        list.add(target);
    }

    public static void clear() {
        collisionMap.clear();
    }

    public static List<Entity> getCollisionList(Entity source, Level level) {
        IntArrayList ids = collisionMap.get(TempID.getId(source));
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        return new EntityListView(ids, level, source);
    }

    private static class EntityListView extends AbstractList<Entity> {
        private final IntArrayList ids;
        private final Level level;
        private final Entity source;

        public EntityListView(IntArrayList ids, Level level, Entity source) {
            this.ids = ids;
            this.level = level;
            this.source = source;
        }

        @Override
        public Entity get(int index) {
            int entityId = ids.getInt(index);
            Entity target = TempID.getEntity(entityId);
            if (target == null) return source;
            return target;
        }

        @Override
        public int size() {
            return ids.size();
        }

        @Override
        public boolean isEmpty() {
            return ids.isEmpty();
        }
    }
}

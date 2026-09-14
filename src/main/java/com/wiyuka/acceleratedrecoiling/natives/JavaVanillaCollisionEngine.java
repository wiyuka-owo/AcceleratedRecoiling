package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.algorithm.CollisionConfig;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionEngine;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;

public class JavaVanillaCollisionEngine implements CollisionEngine {
    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1, 0);
    private final IdentityHashMap<Entity, ObjectArrayList<Entity>> neighborLists = new IdentityHashMap<>();
    private final ObjectOpenHashSet<Entity> bakeSeen = new ObjectOpenHashSet<>();
    private final ArrayList<Entity> queryOut = new ArrayList<>();
    private final CollisionResult emptyResult = new EmptyResult();

    @Override
    public String getName() {
        return "JavaVanilla";
    }

    @Override
    public void initialize() {
    }

    @Override
    public void setConfig(CollisionConfig config) {
        this.config = config;
    }

    @Override
    public void destroy() {
        neighborLists.clear();
        bakeSeen.clear();
        queryOut.clear();
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        resultSizeOut[0] = 0;
        return emptyResult;
    }

    public void rebuild(List<Entity> entities) {
        neighborLists.clear();
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Long2ObjectOpenHashMap<ObjectArrayList<Entity>> cells = new Long2ObjectOpenHashMap<>();
        IdentityHashMap<Entity, long[]> membership = new IdentityHashMap<>();
        ArrayList<Entity> packed = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) {
                insert(cells, membership, entity, entity.getBoundingBox());
                packed.add(entity);
            }
        }
        int K = this.config.maxCollision();
        for (Entity self : packed) {
            long[] keys = membership.get(self);
            if (keys == null) {
                continue;
            }
            bakeSeen.clear();
            ObjectArrayList<Entity> list = new ObjectArrayList<>();
            for (int k = 0; k < keys.length && list.size() < K; k++) {
                ObjectArrayList<Entity> bucket = cells.get(keys[k]);
                if (bucket == null) {
                    continue;
                }
                for (int i = 0, size = bucket.size(); i < size && list.size() < K; i++) {
                    Entity other = bucket.get(i);
                    if (other == self || !bakeSeen.add(other)) {
                        continue;
                    }
                    list.add(other);
                }
            }
            if (!list.isEmpty()) {
                neighborLists.put(self, list);
            }
        }
    }

    public void relocate(Entity entity) {
    }

    public List<Entity> neighbors(Entity self, AABB box, List<Entity> ignored) {
        queryOut.clear();
        if (self == null || box == null) {
            return queryOut;
        }
        ObjectArrayList<Entity> raw = neighborLists.get(self);
        if (raw == null || raw.isEmpty()) {
            return queryOut;
        }
        int K = this.config.maxCollision();
        Predicate<? super Entity> pushable = EntitySelector.pushableBy(self);
        for (int i = 0, size = raw.size(); i < size && queryOut.size() < K; i++) {
            Entity other = raw.get(i);
            if (other == self || other.isRemoved()) {
                continue;
            }
            if (box.intersects(other.getBoundingBox()) && pushable.test(other)) {
                queryOut.add(other);
            }
        }
        return queryOut;
    }

    private void insert(
            Long2ObjectOpenHashMap<ObjectArrayList<Entity>> cells,
            IdentityHashMap<Entity, long[]> membership,
            Entity entity,
            AABB box
    ) {
        double g = gridSize();
        int x0 = floorCell(box.minX, g);
        int x1 = floorCell(box.maxX, g);
        int z0 = floorCell(box.minZ, g);
        int z1 = floorCell(box.maxZ, g);
        long[] keys = new long[(x1 - x0 + 1) * (z1 - z0 + 1)];
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                long key = pack(x, z);
                keys[n++] = key;
                ObjectArrayList<Entity> bucket = cells.get(key);
                if (bucket == null) {
                    bucket = new ObjectArrayList<>();
                    cells.put(key, bucket);
                }
                bucket.add(entity);
            }
        }
        membership.put(entity, keys);
    }

    private double gridSize() {
        int size = this.config.gridSize();
        return size <= 0 ? 1.0 : size;
    }

    private static int floorCell(double value, double grid) {
        return (int) Math.floor(value / grid);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static class EmptyResult implements CollisionResult {
        @Override
        public int getA(int index) {
            return 0;
        }

        @Override
        public int getB(int index) {
            return 0;
        }

        @Override
        public float getDensity(int entityIndex) {
            return 0;
        }

        @Override
        public void copyATo(int[] dest, int length) {
        }

        @Override
        public void copyBTo(int[] dest, int length) {
        }

        @Override
        public void copyDensityTo(float[] dest, int length) {
        }
    }
}

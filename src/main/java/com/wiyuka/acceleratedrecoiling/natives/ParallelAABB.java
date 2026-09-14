package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.algorithm.CollisionResult;
import com.wiyuka.acceleratedrecoiling.api.ICustomData;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

public class ParallelAABB {
    static boolean isInitialized = false;
    private static List<Entity> tickEntities = List.of();

    public static List<Entity> getTickEntities() {
        return tickEntities;
    }

    public static void clearTickEntities() {
        tickEntities = List.of();
    }

    public static void handleEntityPush(final List<Entity> livingEntities, final List<Entity> queryEntities, double inflate) {
        tickEntities = queryEntities;
        CollisionMapData.clear();

        if (!isInitialized) {
            NativeInterface.initialize();
            isInitialized = true;
        }

        if (NativeInterface.isJavaVanilla()) {
            NativeInterface.rebuildJavaVanilla(queryEntities);
            return;
        }

        int[] resultCounts = new int[1];
        double[] aabb = new double[livingEntities.size() * 6];
        double[] locations = new double[livingEntities.size() * 3];

        int extractIndex = 0;
        for (Entity entity : livingEntities) {
            ICustomData customBB = (ICustomData) entity;
            customBB.extractionBoundingBox(aabb, extractIndex * 6, inflate);
            customBB.extractionPosition(locations, extractIndex * 3);
            customBB.setDensity(0);
            extractIndex++;
        }
        CollisionResult result = NativeInterface.push(locations, aabb, resultCounts);
        if (result == null) return;

        int index = 0;
        for (Entity entity : livingEntities) {
            ICustomData customBB = (ICustomData) entity;
            float currentDensity = result.getDensity(index);
            customBB.setDensity(currentDensity);

            if (FoldConfig.debugDensity) {
                Component debugName = Component.literal("Density: ")
                        .withStyle(ChatFormatting.GREEN)
                        .append(Component.literal(String.format("%.2f", currentDensity))
                                .withStyle(ChatFormatting.YELLOW));
                entity.setCustomName(debugName);
                entity.setCustomNameVisible(true);
            }
            index++;
        }

        for (int i = 0; i < resultCounts[0]; i++) {
            int e1Index = result.getA(i);
            int e2Index = result.getB(i);
            if (e1Index >= livingEntities.size() || e2Index >= livingEntities.size()) continue;

            Entity e1 = livingEntities.get(e1Index);
            Entity e2 = livingEntities.get(e2Index);

            LivingEntity livingEntity;
            Entity entity;
            if (e1 instanceof LivingEntity) {
                livingEntity = (LivingEntity) e1;
                entity = e2;
            } else if (e2 instanceof LivingEntity) {
                livingEntity = (LivingEntity) e2;
                entity = e1;
            } else continue;

            if (EntitySelector.pushableBy(livingEntity).test(entity))
                CollisionMapData.putCollision(TempID.getId(livingEntity), TempID.getId(entity));
        }
    }

    public static CollisionResult nativePush(double[] positions, double[] aabbs, int[] resultSizeOut) {
        if (!isInitialized) {
            NativeInterface.initialize();
            isInitialized = true;
        }
        return NativeInterface.push(positions, aabbs, resultSizeOut);
    }
}

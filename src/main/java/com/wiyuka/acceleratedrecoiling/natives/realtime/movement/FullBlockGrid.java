package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class FullBlockGrid {
    static final int ROW_SHIFT = 3;
    static final int EDGE = 1 << ROW_SHIFT;
    private static final int LAYER_SHIFT = ROW_SHIFT * 2;

    final int minX;
    final int minY;
    final int minZ;

    private final long[] occupiedLayers = new long[EDGE];
    private VoxelShape[] cachedShapes;

    FullBlockGrid(int minX, int minY, int minZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
    }

    void add(int x, int y, int z) {
        int localX = x - minX;
        int localY = y - minY;
        int localZ = z - minZ;

        int bitIndex = localY * EDGE + localX;
        occupiedLayers[localZ] |= 1L << bitIndex;
    }

    void addRow(int y, int z, int rowBits) {
        int localY = y - minY;
        int localZ = z - minZ;

        int bitOffset = localY * EDGE;
        occupiedLayers[localZ] |= (long) rowBits << bitOffset;
    }

    FullBlockCollisions intersect(AABB area) {
        long[] intersectingLayers = null;
        int collisionCount = 0;

        for (int localZ = 0; localZ < EDGE; localZ++) {
            int blockZ = minZ + localZ;
            boolean overlapsZ = area.minZ < blockZ + 1.0 && area.maxZ > blockZ;
            if (!overlapsZ) {
                continue;
            }

            long remainingBlocks = occupiedLayers[localZ];
            while (remainingBlocks != 0) {
                int bitIndex = Long.numberOfTrailingZeros(remainingBlocks);
                remainingBlocks &= remainingBlocks - 1;

                int localX = bitIndex & (EDGE - 1);
                int localY = bitIndex >> ROW_SHIFT;
                int blockX = minX + localX;
                int blockY = minY + localY;

                if (!(area.minX < blockX + 1.0 && area.maxX > blockX)) {
                    continue;
                }

                if (!(area.minY < blockY + 1.0 && area.maxY > blockY)) {
                    continue;
                }

                if (intersectingLayers == null) intersectingLayers = new long[EDGE];

                intersectingLayers[localZ] |= 1L << bitIndex;
                collisionCount++;
            }
        }

        if (intersectingLayers == null) {
            return FullBlockCollisions.EMPTY;
        }
        return new FullBlockCollisions(this, intersectingLayers, collisionCount);
    }

    BlockPos position(int cellIndex) {
        int localX = cellIndex & (EDGE - 1);
        int localY = (cellIndex >> ROW_SHIFT) & (EDGE - 1);
        int localZ = cellIndex >> LAYER_SHIFT;

        return new BlockPos(minX + localX, minY + localY, minZ + localZ);
    }

    VoxelShape shape(int cellIndex) {
        if (cachedShapes == null) {
            cachedShapes = new VoxelShape[EDGE * EDGE * EDGE];
        }

        var shape = cachedShapes[cellIndex];
        if (shape == null) {
            shape = Shapes.block().move(position(cellIndex));
            cachedShapes[cellIndex] = shape;
        }
        return shape;
    }
}

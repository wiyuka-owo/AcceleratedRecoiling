package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FullBlockCollisions extends AbstractList<VoxelShape> {
    static final FullBlockCollisions EMPTY = new FullBlockCollisions(new FullBlockGrid(0, 0, 0), new long[0], 0);
    private static final double EPSILON = 1.0E-7;
    private static final Direction.Axis[] YXZ_ORDER = {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z};
    private static final Direction.Axis[] YZX_ORDER = {Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X};

    private final FullBlockGrid grid;
    private final long[] collisionLayers;
    private final int size;
    private VoxelShape[] cachedShapes;

    FullBlockCollisions(FullBlockGrid grid, long[] collisionLayers, int size) {
        this.grid = grid;
        this.collisionLayers = collisionLayers;
        this.size = size;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public VoxelShape get(int index) {
        Objects.checkIndex(index, size);

        if (cachedShapes == null) {
            initializeShapes();
        }

        return cachedShapes[index];
    }

    private void initializeShapes() {
        cachedShapes = new VoxelShape[size];
        int shapeIndex = 0;

        for (int localZ = 0; localZ < collisionLayers.length; localZ++) {
            long remainingBlocks = collisionLayers[localZ];

            while (remainingBlocks != 0) {
                int bitIndex = Long.numberOfTrailingZeros(remainingBlocks);
                remainingBlocks &= remainingBlocks - 1;

                int cellIndex = localZ * Long.SIZE + bitIndex;
                cachedShapes[shapeIndex++] = grid.shape(cellIndex);
            }
        }
    }

    public Vec3 collide(Vec3 movement, AABB box) {
        if (isEmpty()) {
            return movement;
        }

        Vec3 resolved = Vec3.ZERO;
        var axisOrder = Math.abs(movement.x) < Math.abs(movement.z) ? YZX_ORDER : YXZ_ORDER;
        for (var axis : axisOrder) {
            double distance = movement.get(axis);
            if (distance == 0.0) {
                continue;
            }

            double clipped = clip(axis, box.move(resolved), distance);
            resolved = resolved.with(axis, clipped);
        }

        return resolved;
    }

    private double clip(Direction.Axis axis, AABB box, double distance) {
        double boxMin = box.min(axis);
        double boxMax = box.max(axis);

        for (int localZ = 0; localZ < collisionLayers.length; localZ++) {
            long remainingBlocks = collisionLayers[localZ];

            while (remainingBlocks != 0) {
                if (Math.abs(distance) < EPSILON) {
                    return 0.0;
                }

                int bitIndex = Long.numberOfTrailingZeros(remainingBlocks);
                remainingBlocks &= remainingBlocks - 1;

                int blockX = grid.minX + (bitIndex & (FullBlockGrid.EDGE - 1));
                int blockY = grid.minY + (bitIndex >> FullBlockGrid.ROW_SHIFT);
                int blockZ = grid.minZ + localZ;

                boolean overlapsOtherAxes = switch (axis) {
                    case X -> overlaps(box.minY, box.maxY, blockY) && overlaps(box.minZ, box.maxZ, blockZ);
                    case Y -> overlaps(box.minX, box.maxX, blockX) && overlaps(box.minZ, box.maxZ, blockZ);
                    case Z -> overlaps(box.minX, box.maxX, blockX) && overlaps(box.minY, box.maxY, blockY);
                };
                if (!overlapsOtherAxes) {
                    continue;
                }

                int blockMin = axis.choose(blockX, blockY, blockZ);
                distance = clipDistance(distance, boxMin, boxMax, blockMin);
            }
        }

        return distance;
    }

    private static double clipDistance(double distance, double boxMin, double boxMax, int blockMin) {
        double blockMax = blockMin + 1.0;

        if (distance > 0.0 && boxMax - EPSILON < blockMin) {
            double gap = blockMin - boxMax;
            if (gap >= -EPSILON) {
                return Math.min(distance, gap);
            }
        } else if (distance < 0.0 && boxMin + EPSILON >= blockMax) {
            double gap = blockMax - boxMin;
            if (gap <= EPSILON) {
                return Math.max(distance, gap);
            }
        }

        return distance;
    }

    private static boolean overlaps(double min, double max, int blockMin) {
        return min + EPSILON < blockMin + 1.0 && max - EPSILON >= blockMin;
    }

    public float[] stepHeights(AABB box, float maxHeight, float skippedHeight) {
        int remainingSurfaces = surfaceMask();
        float[] heights = new float[Integer.bitCount(remainingSurfaces)];
        int count = 0;

        while (remainingSurfaces != 0) {
            int localY = Integer.numberOfTrailingZeros(remainingSurfaces);
            remainingSurfaces &= remainingSurfaces - 1;

            float height = (float) (grid.minY + (double) localY - box.minY);
            if (height < 0.0f || height == skippedHeight) {
                continue;
            }
            if (height > maxHeight) {
                break;
            }
            if (count > 0 && Float.compare(height, heights[count - 1]) == 0) {
                continue;
            }

            heights[count++] = height;
        }

        return count == heights.length ? heights : Arrays.copyOf(heights, count);
    }

    private int surfaceMask() {
        long occupiedRows = 0;
        for (long layer : collisionLayers) {
            occupiedRows |= layer;
        }

        int surfaceMask = 0;
        long rowMask = (1L << FullBlockGrid.EDGE) - 1;
        for (int localY = 0; localY < FullBlockGrid.EDGE; localY++) {
            if ((occupiedRows & rowMask) != 0) {
                int bottomFace = 1 << localY;
                int topFace = 1 << (localY + 1);
                surfaceMask |= bottomFace | topFace;
            }
            occupiedRows >>>= FullBlockGrid.EDGE;
        }

        return surfaceMask;
    }

    public Optional<BlockPos> supportingBlock(Vec3 position) {
        BlockPos nearestBlock = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int localZ = 0; localZ < collisionLayers.length; localZ++) {
            long remainingBlocks = collisionLayers[localZ];

            while (remainingBlocks != 0) {
                int bitIndex = Long.numberOfTrailingZeros(remainingBlocks);
                remainingBlocks &= remainingBlocks - 1;

                int cellIndex = localZ * Long.SIZE + bitIndex;
                BlockPos candidate = grid.position(cellIndex);
                double distance = candidate.distToCenterSqr(position);

                boolean closer = distance < nearestDistance;
                boolean preferredOnTie = distance == nearestDistance
                        && (nearestBlock == null || nearestBlock.compareTo(candidate) < 0);

                if (closer || preferredOnTie) {
                    nearestBlock = candidate;
                    nearestDistance = distance;
                }
            }
        }

        return Optional.ofNullable(nearestBlock);
    }
}

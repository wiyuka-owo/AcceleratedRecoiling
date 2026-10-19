package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import com.wiyuka.acceleratedrecoiling.mixin.VoxelShapeAccess;
import com.wiyuka.acceleratedrecoiling.natives.realtime.CollisionResult;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubePointRange;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ShapeIntersection {
    private static final double EPSILON = 1.0E-7;

    private ShapeIntersection() {
    }

    public static CollisionResult test(VoxelShape shape, VoxelShape box) {
        if (!standard(shape) || !standard(box)) {
            return CollisionResult.VANILLA;
        }

        var boxGrid = ((VoxelShapeAccess) box).ar$grid();
        var grid = ((VoxelShapeAccess) shape).ar$grid();
        if (boxGrid.getClass() != BitSetDiscreteVoxelShape.class || grid.getClass() != BitSetDiscreteVoxelShape.class) {
            return CollisionResult.VANILLA;
        }
        if (boxGrid.getXSize() != 1 || boxGrid.getYSize() != 1 || boxGrid.getZSize() != 1
                || grid.getXSize() > Long.SIZE || grid.getYSize() > Long.SIZE || grid.getZSize() > Long.SIZE) {
            return CollisionResult.VANILLA;
        }
        if (shape.isEmpty() || box.isEmpty()) {
            return CollisionResult.REJECTED;
        }
        if (shape == box) {
            return CollisionResult.ACCEPTED;
        }

        long xCells = overlappingCells(shape.getCoords(Axis.X), box.getCoords(Axis.X));
        if (xCells == 0) {
            return CollisionResult.REJECTED;
        }

        long yCells = overlappingCells(shape.getCoords(Axis.Y), box.getCoords(Axis.Y));
        if (yCells == 0) {
            return CollisionResult.REJECTED;
        }

        long zCells = overlappingCells(shape.getCoords(Axis.Z), box.getCoords(Axis.Z));
        if (zCells == 0) {
            return CollisionResult.REJECTED;
        }

        for (long remainingX = xCells; remainingX != 0; remainingX &= remainingX - 1) {
            int x = Long.numberOfTrailingZeros(remainingX);

            for (long remainingY = yCells; remainingY != 0; remainingY &= remainingY - 1) {
                int y = Long.numberOfTrailingZeros(remainingY);

                for (long remainingZ = zCells; remainingZ != 0; remainingZ &= remainingZ - 1) {
                    int z = Long.numberOfTrailingZeros(remainingZ);
                    if (grid.isFull(x, y, z)) {
                        return CollisionResult.ACCEPTED;
                    }
                }
            }
        }

        return CollisionResult.REJECTED;
    }

    private static boolean standard(VoxelShape shape) {
        return shape.getClass() == ArrayVoxelShape.class || shape.getClass() == CubeVoxelShape.class;
    }

    private static long overlappingCells(DoubleList coordinates, DoubleList box) {
        int cellCount = coordinates.size() - 1;
        if (coordinates instanceof CubePointRange && box instanceof CubePointRange) {
            return -1L >>> (Long.SIZE - cellCount);
        }
        if (cellCount == 1 && coordinates.equals(box)) {
            return 1;
        }

        double boxMin = box.getDouble(0);
        double boxMax = box.getDouble(1);
        int point = 0;
        while (point <= cellCount && coordinates.getDouble(point) < boxMin + EPSILON) {
            point++;
        }

        long cells = 0;
        double previousCoordinate = point == 0 ? Double.NaN : boxMin;
        while (point <= cellCount) {
            double coordinate = coordinates.getDouble(point);
            boolean atBoxEnd = !(coordinate < boxMax + EPSILON);
            if (atBoxEnd) {
                coordinate = boxMax;
            }

            if (!(previousCoordinate >= coordinate - EPSILON)) {
                if (point > 0) {
                    cells |= 1L << (point - 1);
                }
                previousCoordinate = coordinate;
            }

            if (atBoxEnd) {
                break;
            }
            point++;
        }

        return cells;
    }
}

package com.wiyuka.acceleratedrecoiling.natives.realtime.index;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.core.BlockPos;


/*
 * 由 RealtimeSection.View 持有, 该算法将 Section 的三个坐标轴各划分为 32 个区间 (CELL_COUNT)，
 * 并为每个轴的起点和终点分别维护 32 行位图，位图中的每一位对应 View 的一个实体槽位
 * 位图采用累积记录方式：每一行包含端点落在当前区间的实体，同时包含前面所有区间的实体
 *
 * 反向缓存每个实体端点所在的区间，因此实体移动跨区时，只需更新受影响的位图行
 * 查询时，根据查询范围所在的区间，读取对应的起点和终点位图，
 * 通过位运算合并三个轴的筛选结果 对于仅靠位图无法确定边界的候选实体（比如对方的起点就是我的终点），最后再用原始坐标进行相交检测
 */

final class SpatialIndex {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("ar.spatialIndex", "true"));
    private static final int MIN_ENTITIES = 1024;

    private static final int AXIS_COUNT = 3;
    private static final int BOUNDS_FIELD_COUNT = AXIS_COUNT * 2;
    private static final int CELL_SHIFT = 5;
    private static final int CELL_COUNT = 32;
    private static final int SCALE = 64;
    private static final byte UNSET_CELL = CELL_COUNT;

    private static final int WORD_COUNT_OFFSET = AXIS_COUNT * Long.BYTES;
    private static final int CELL_COUNT_OFFSET = WORD_COUNT_OFFSET + Integer.BYTES;
    private static final int CELL_SHIFT_OFFSET = CELL_COUNT_OFFSET + Integer.BYTES;
    private static final int RESERVED_OFFSET = CELL_SHIFT_OFFSET + Integer.BYTES;
    private static final int HEADER_BYTES = RESERVED_OFFSET + Integer.BYTES;

    private static final int EMPTY_ROW = BOUNDS_FIELD_COUNT * CELL_COUNT;
    private static final int NEEDS_EXACT_BOUNDS_ROW = EMPTY_ROW + 1;
    private static final int ELIGIBLE_ROW = NEEDS_EXACT_BOUNDS_ROW + 1;
    private static final int FALLBACK_ROW = ELIGIBLE_ROW + 1;
    private static final int BITMAP_ROW_COUNT = FALLBACK_ROW + 1;

    private enum BoundsField {
        MIN_X, MIN_Y, MIN_Z,
        MAX_X, MAX_Y, MAX_Z
    }

    private final long[] scaledOrigins;
    private final int bytesPerRow;
    private final ByteBuffer bitmaps;
    private final long nativeAddress;
    private final byte[] boundCells;

    private final PositionGroups groups;

    static boolean enabled(int entities) {
        return ENABLED && entities >= MIN_ENTITIES && RealtimeNative.usesSimd();
    }

    SpatialIndex(int capacity, BlockPos origin) {
        scaledOrigins = new long[] {
                (long) origin.getX() * SCALE,
                (long) origin.getY() * SCALE,
                (long) origin.getZ() * SCALE
        };

        int wordsPerRow = (capacity + Long.SIZE - 1) / Long.SIZE;
        bytesPerRow = wordsPerRow * Long.BYTES;
        int bufferBytes = HEADER_BYTES + BITMAP_ROW_COUNT * bytesPerRow;
        bitmaps = ByteBuffer.allocateDirect(bufferBytes).order(ByteOrder.nativeOrder());

        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            bitmaps.putLong(axis * Long.BYTES, scaledOrigins[axis]);
        }
        bitmaps.putInt(WORD_COUNT_OFFSET, wordsPerRow);
        bitmaps.putInt(CELL_COUNT_OFFSET, CELL_COUNT);
        bitmaps.putInt(CELL_SHIFT_OFFSET, CELL_SHIFT);
        nativeAddress = RealtimeNative.address(bitmaps);

        boundCells = new byte[capacity * BOUNDS_FIELD_COUNT];
        Arrays.fill(boundCells, UNSET_CELL);
        groups = new PositionGroups(capacity, bufferBytes);
    }

    long address() {
        return nativeAddress;
    }

    long coincidentAddress(double x, double z) {
        return groups.coincidentAddress(x, z);
    }

    void updateBounds(int slot, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean finite) {
        updateCell(slot, BoundsField.MIN_X, minX);
        updateCell(slot, BoundsField.MIN_Y, minY);
        updateCell(slot, BoundsField.MIN_Z, minZ);
        updateCell(slot, BoundsField.MAX_X, maxX);
        updateCell(slot, BoundsField.MAX_Y, maxY);
        updateCell(slot, BoundsField.MAX_Z, maxZ);

        setRowBit(NEEDS_EXACT_BOUNDS_ROW, slot, !finite);
    }

    void updateState(int slot, double x, double z, double flag) {
        boolean finitePosition = Double.isFinite(x) && Double.isFinite(z);
        boolean requiresFallback = flag < 0 || (flag != 0 && !finitePosition);
        boolean eligible = flag != 0 && !requiresFallback;

        setRowBit(ELIGIBLE_ROW, slot, eligible);
        setRowBit(FALLBACK_ROW, slot, requiresFallback);

        groups.update(slot, x, z, eligible);
    }

    void remove(int slot) {
        setRowBit(ELIGIBLE_ROW, slot, false);
        setRowBit(FALLBACK_ROW, slot, false);

        groups.remove(slot);
    }

    private void updateCell(int slot, BoundsField field, int coordinate) {
        int fieldIndex = field.ordinal();
        int axis = fieldIndex % AXIS_COUNT;
        long localCell = (coordinate - scaledOrigins[axis]) >> CELL_SHIFT;
        int newCell = (int) Math.clamp(localCell, 0, CELL_COUNT - 1);

        int cellOffset = slot * BOUNDS_FIELD_COUNT + fieldIndex;
        int previousCell = boundCells[cellOffset];
        if (previousCell == newCell) {
            return;
        }

        boundCells[cellOffset] = (byte) newCell;

        int firstBoundary = Math.min(previousCell, newCell);
        int endBoundary = Math.max(previousCell, newCell);
        int firstFieldRow = fieldIndex * CELL_COUNT;
        for (int boundary = firstBoundary; boundary < endBoundary; boundary++) {
            setRowBit(firstFieldRow + boundary, slot, newCell < previousCell);
        }
    }

    private void setRowBit(int row, int slot, boolean set) {
        int rowOffset = HEADER_BYTES + row * bytesPerRow;
        int wordOffset = slot / Long.SIZE * Long.BYTES;
        int byteOffset = rowOffset + wordOffset;

        long bitMask = 1L << (slot % Long.SIZE);
        long currentWord = bitmaps.getLong(byteOffset);
        long updatedWord = set ? currentWord | bitMask : currentWord & ~bitMask;
        if (currentWord != updatedWord) {
            bitmaps.putLong(byteOffset, updatedWord);
        }
    }
}

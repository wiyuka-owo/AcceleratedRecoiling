package com.wiyuka.acceleratedrecoiling.algorithm;

import java.util.Arrays;
import java.util.stream.IntStream;

public class JavaCollisionEngine implements CollisionEngine {
    private static final double WORLD_OFFSET = 50000000.0;
    private static final int SCALE = 64;
    private static final int BITS_X = 36;
    private static final long MASK_X = (1L << BITS_X) - 1L;

    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1);
    private EntityData mem;
    private int[] outputA;
    private int[] outputB;
    private float[] densityBuf;
    private final PushResultJava resultWrapper = new PushResultJava();

    @Override
    public String getName() {
        return "Java";
    }

    @Override
    public void initialize() {
        mem = new EntityData();
        ensureOutputSize(1024);
    }

    @Override
    public void setConfig(CollisionConfig config) {
        this.config = config;
    }

    @Override
    public void destroy() {
        mem = null;
        outputA = null;
        outputB = null;
        densityBuf = null;
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        if (aabb == null || aabb.length < 12 || mem == null || outputA == null || outputB == null || densityBuf == null) {
            resultSizeOut[0] = 0;
            return resultWrapper;
        }

        int entityCount = aabb.length / 6;
        int K = this.config.maxCollision();
        double invGridSize = 1.0 / this.config.gridSize();

        ensureOutputSize(entityCount);
        mem.ensureSize(entityCount);

        IntStream.range(0, entityCount).parallel().forEach(i -> {
            mem.originalIds[i] = i;

            int base = i * 6;
            double dMinX = aabb[base];
            double dMinY = aabb[base + 1];
            double dMinZ = aabb[base + 2];
            double dMaxX = aabb[base + 3];
            double dMaxY = aabb[base + 4];
            double dMaxZ = aabb[base + 5];

            long qX = (long) ((dMinX + WORLD_OFFSET) * SCALE);
            long gridZ = (long) ((dMinZ + WORLD_OFFSET) * invGridSize);

            long key = (gridZ << BITS_X) | (qX & MASK_X);
            mem.sortKeys[i] = key;

            mem.quantizedMinX[i] = (int) (dMinX * SCALE);
            mem.quantizedMinY[i] = (int) (dMinY * SCALE);
            mem.quantizedMinZ[i] = (int) (dMinZ * SCALE);
            mem.quantizedMaxX[i] = (int) (dMaxX * SCALE);
            mem.quantizedMaxY[i] = (int) (dMaxY * SCALE);
            mem.quantizedMaxZ[i] = (int) (dMaxZ * SCALE);
        });

        radixSort64(mem.sortKeys, mem.originalIds, mem.sortKeyBuffer, mem.originalIdBuffer, entityCount);

        mem.runStartsCount = 0;
        mem.runStarts[mem.runStartsCount++] = 0;
        int runIndex = 0;
        long currentGrid = mem.sortKeys[0] >>> BITS_X;

        for (int i = 0; i < entityCount; ++i) {
            long g = mem.sortKeys[i] >>> BITS_X;
            if (g != currentGrid) {
                mem.runStarts[mem.runStartsCount++] = i;
                currentGrid = g;
                runIndex++;
            }
            mem.runIndexPerItem[i] = runIndex;
        }
        mem.runStarts[mem.runStartsCount++] = entityCount;

        IntStream.range(0, entityCount).parallel().forEach(i -> {
            int originalID = mem.originalIds[i];
            mem.sortedMinX[i] = mem.quantizedMinX[originalID];
            mem.sortedMinY[i] = mem.quantizedMinY[originalID];
            mem.sortedMinZ[i] = mem.quantizedMinZ[originalID];
            mem.sortedMaxX[i] = mem.quantizedMaxX[originalID];
            mem.sortedMaxY[i] = mem.quantizedMaxY[originalID];
            mem.sortedMaxZ[i] = mem.quantizedMaxZ[originalID];
        });

        final int WINDOW = this.config.densityWindow();
        final float EPSILON_DISTANCE = 0.1f;
        IntStream.range(0, mem.runStartsCount - 1).parallel().forEach(grid -> {
            int startIdx = mem.runStarts[grid];
            int endIdx = mem.runStarts[grid + 1];
            for (int i = startIdx; i < endIdx; ++i) {
                int left = Math.max(startIdx, i - WINDOW);
                int right = Math.min(endIdx - 1, i + WINDOW);
                int count = right - left + 1;

                if (count <= 1) {
                    densityBuf[mem.originalIds[i]] = 0.0f;
                    continue;
                }

                int dx_quantized = mem.sortedMinX[right] - mem.sortedMinX[left];
                float dx_real = (float) dx_quantized / SCALE;
                densityBuf[mem.originalIds[i]] = count / (dx_real + EPSILON_DISTANCE);
            }
        });

        IntStream.range(0, entityCount).parallel().forEach(i -> {
            int idA = mem.originalIds[i];
            int maxXA = mem.sortedMaxX[i];
            int minYA = mem.sortedMinY[i];
            int maxYA = mem.sortedMaxY[i];
            int minZA = mem.sortedMinZ[i];
            int maxZA = mem.sortedMaxZ[i];
            int minXA = mem.sortedMinX[i];

            int writeOffset = i * K;
            int currentCollisions = 0;

            int myRun = mem.runIndexPerItem[i];
            int endOfMyGrid = mem.runStarts[myRun + 1];

            int j = i + 1;
            while (j < endOfMyGrid && currentCollisions < K) {
                if (mem.sortedMinX[j] > maxXA) break;
                if (!(maxXA <= mem.sortedMinX[j] || minXA >= mem.sortedMaxX[j] ||
                        maxYA <= mem.sortedMinY[j] || minYA >= mem.sortedMaxY[j] ||
                        maxZA <= mem.sortedMinZ[j] || minZA >= mem.sortedMaxZ[j])) {
                    outputA[writeOffset + currentCollisions] = idA;
                    outputB[writeOffset + currentCollisions] = mem.originalIds[j];
                    currentCollisions++;
                }
                j++;
            }

            if (currentCollisions < K && myRun + 2 < mem.runStartsCount) {
                int startNext = mem.runStarts[myRun + 1];
                int endNext = mem.runStarts[myRun + 2];
                j = startNext;
                while (j < endNext && currentCollisions < K) {
                    if (mem.sortedMinX[j] > maxXA) break;
                    if (!(maxXA <= mem.sortedMinX[j] || minXA >= mem.sortedMaxX[j] ||
                            maxYA <= mem.sortedMinY[j] || minYA >= mem.sortedMaxY[j] ||
                            maxZA <= mem.sortedMinZ[j] || minZA >= mem.sortedMaxZ[j])) {
                        outputA[writeOffset + currentCollisions] = idA;
                        outputB[writeOffset + currentCollisions] = mem.originalIds[j];
                        currentCollisions++;
                    }
                    j++;
                }
            }
            mem.collisionCounts[i] = currentCollisions;
        });

        int currentOffset = 0;
        for (int i = 0; i < entityCount; ++i) {
            int count = mem.collisionCounts[i];
            if (count > 0) {
                int srcOffset = i * K;
                if (srcOffset != currentOffset) {
                    System.arraycopy(outputA, srcOffset, outputA, currentOffset, count);
                    System.arraycopy(outputB, srcOffset, outputB, currentOffset, count);
                }
                currentOffset += count;
            }
        }

        resultSizeOut[0] = currentOffset;
        return resultWrapper;
    }

    private void ensureOutputSize(int entityCount) {
        int requiredCollisionSize = entityCount * config.maxCollision();
        if (outputA == null || outputA.length < requiredCollisionSize) {
            int newSize = Math.max(requiredCollisionSize, outputA == null ? 0 : (int) (outputA.length * 1.5));
            outputA = new int[newSize];
            outputB = new int[newSize];
        }
        if (densityBuf == null || densityBuf.length < entityCount) {
            int newSize = Math.max(entityCount, densityBuf == null ? 0 : (int) (densityBuf.length * 1.5));
            densityBuf = new float[newSize];
        }
    }

    private static void radixSort64(long[] keys, int[] vals, long[] keysBuf, int[] valsBuf, int n) {
        int[] histogram = new int[256];
        long[] srcKeys = keys;
        int[] srcVals = vals;
        long[] dstKeys = keysBuf;
        int[] dstVals = valsBuf;

        for (int pass = 0; pass < 8; pass++) {
            int shift = pass * 8;
            Arrays.fill(histogram, 0);

            for (int i = 0; i < n; i++) {
                histogram[(int) ((srcKeys[i] >>> shift) & 0xFF)]++;
            }

            int offset = 0;
            for (int i = 0; i < 256; i++) {
                int count = histogram[i];
                histogram[i] = offset;
                offset += count;
            }

            for (int i = 0; i < n; i++) {
                int pos = (int) ((srcKeys[i] >>> shift) & 0xFF);
                int destIdx = histogram[pos]++;
                dstKeys[destIdx] = srcKeys[i];
                dstVals[destIdx] = srcVals[i];
            }

            long[] tempKeys = srcKeys;
            srcKeys = dstKeys;
            dstKeys = tempKeys;
            int[] tempVals = srcVals;
            srcVals = dstVals;
            dstVals = tempVals;
        }

        if (srcKeys == keysBuf) {
            System.arraycopy(keysBuf, 0, keys, 0, n);
            System.arraycopy(valsBuf, 0, vals, 0, n);
        }
    }

    private static class EntityData {
        int currentSize = -1;
        long[] sortKeys;
        int[] originalIds;
        long[] sortKeyBuffer;
        int[] originalIdBuffer;
        int[] quantizedMinX, quantizedMaxX;
        int[] quantizedMinY, quantizedMaxY;
        int[] quantizedMinZ, quantizedMaxZ;
        int[] sortedMinX, sortedMaxX;
        int[] sortedMinY, sortedMaxY;
        int[] sortedMinZ, sortedMaxZ;
        int[] runIndexPerItem;
        int[] runStarts;
        int runStartsCount;
        int[] collisionCounts;

        public void ensureSize(int n) {
            if (currentSize < n) {
                int newSize = Math.max(n, (int) (currentSize * 1.5));
                sortKeys = new long[newSize];
                originalIds = new int[newSize];
                sortKeyBuffer = new long[newSize];
                originalIdBuffer = new int[newSize];

                quantizedMinX = new int[newSize];
                quantizedMaxX = new int[newSize];
                quantizedMinY = new int[newSize];
                quantizedMaxY = new int[newSize];
                quantizedMinZ = new int[newSize];
                quantizedMaxZ = new int[newSize];

                sortedMinX = new int[newSize];
                sortedMaxX = new int[newSize];
                sortedMinY = new int[newSize];
                sortedMaxY = new int[newSize];
                sortedMinZ = new int[newSize];
                sortedMaxZ = new int[newSize];

                runIndexPerItem = new int[newSize];
                runStarts = new int[newSize + 2];
                collisionCounts = new int[newSize];

                currentSize = newSize;
            }
        }
    }

    private class PushResultJava implements CollisionResult {
        @Override
        public int getA(int index) {
            return outputA[index];
        }

        @Override
        public int getB(int index) {
            return outputB[index];
        }

        @Override
        public float getDensity(int entityIndex) {
            return densityBuf[entityIndex];
        }

        @Override
        public void copyATo(int[] dest, int length) {
            System.arraycopy(outputA, 0, dest, 0, length);
        }

        @Override
        public void copyBTo(int[] dest, int length) {
            System.arraycopy(outputB, 0, dest, 0, length);
        }

        @Override
        public void copyDensityTo(float[] dest, int length) {
            System.arraycopy(densityBuf, 0, dest, 0, length);
        }
    }
}

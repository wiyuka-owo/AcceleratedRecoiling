package com.wiyuka.acceleratedrecoiling.algorithm;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public class JavaSimdCollisionEngine implements CollisionEngine {
    private static final AtomicLong maxSizeTouched = new AtomicLong(-1);
    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1, 0);

    @Override
    public String getName() {
        return "Java SIMD (Vector API)";
    }

    static class PushResultJavaSIMD implements CollisionResult {
        private int[] arrayA;
        private int[] arrayB;
        private float[] arrayDensity;

        private PushResultJavaSIMD() {
        }

        void update(int[] a, int[] b, float[] density) {
            this.arrayA = a;
            this.arrayB = b;
            this.arrayDensity = density;
        }

        @Override
        public int getA(int index) {
            return arrayA[index];
        }

        @Override
        public int getB(int index) {
            return arrayB[index];
        }

        @Override
        public float getDensity(int index) {
            return arrayDensity[index];
        }

        @Override
        public void copyATo(int[] dest, int length) {
            System.arraycopy(arrayA, 0, dest, 0, length);
        }

        @Override
        public void copyBTo(int[] dest, int length) {
            System.arraycopy(arrayB, 0, dest, 0, length);
        }

        @Override
        public void copyDensityTo(float[] dest, int length) {
            System.arraycopy(arrayDensity, 0, dest, 0, length);
        }
    }

    private static class ThreadState {
        int epoch;
        int[] bufA;
        int[] bufB;
        float[] densityBuf;
        final JavaSIMD engine = new JavaSIMD();
        int currentCollisionSize = -1;
        int currentEntitySize = -1;
        final PushResultJavaSIMD resultWrapper = new PushResultJavaSIMD();

        PushResultJavaSIMD reallocOutputBuf(int entityCount, int maxCollisions) {
            if (maxCollisions > currentCollisionSize) {
                int allocSize = Math.max(1024, (int) (maxCollisions * 1.2));
                bufA = new int[allocSize];
                bufB = new int[allocSize];
                currentCollisionSize = allocSize;
            }
            if (entityCount > currentEntitySize) {
                int allocEntity = Math.max(1024, (int) (entityCount * 1.2));
                densityBuf = new float[allocEntity];
                currentEntitySize = allocEntity;
            }
            resultWrapper.update(bufA, bufB, densityBuf);
            return resultWrapper;
        }

        void destroy() {
            bufA = null;
            bufB = null;
            densityBuf = null;
        }
    }

    private static int threadStateEpoch = 0;
    private static final Set<ThreadState> ALL_THREAD_STATES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ThreadState> THREAD_STATE = new ThreadLocal<>();

    private static ThreadState threadState() {
        ThreadState state = THREAD_STATE.get();
        if (state == null || state.epoch != threadStateEpoch) {
            state = new ThreadState();
            state.epoch = threadStateEpoch;
            THREAD_STATE.set(state);
            ALL_THREAD_STATES.add(state);
        }
        return state;
    }

    @Override
    public void initialize() {
        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        try {
            int dummy = IntVector.SPECIES_256.length();
            logger.log(Level.INFO, "Java SIMD Vector API Backend initialized successfully. (Vector length: {0})", dummy);
        } catch (Throwable e) {
            throw new RuntimeException("Vector API not available, Did you add '--add-modules jdk.incubator.vector' to JVM arguments?", e);
        }
    }

    @Override
    public void setConfig(CollisionConfig config) {
        this.config = config;
    }

    @Override
    public void destroy() {
        for (ThreadState state : ALL_THREAD_STATES) {
            state.destroy();
        }
        ALL_THREAD_STATES.clear();
        threadStateEpoch++;
        maxSizeTouched.set(-1);
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        ThreadState state = threadState();

        int count = locations.length / 3;
        int maxResultSize = count * config.maxCollision();
        maxSizeTouched.updateAndGet(current -> Math.max(current, count));

        PushResultJavaSIMD collisionPairs = state.reallocOutputBuf(count, maxResultSize);

        try {
            int collisionSize = state.engine.push(
                    aabb,
                    collisionPairs.arrayA,
                    collisionPairs.arrayB,
                    collisionPairs.arrayDensity,
                    count,
                    config.maxCollision(),
                    config.gridSize(),
                    config.densityWindow()
            );

            resultSizeOut[0] = collisionSize;
            if (collisionSize == -1) return null;

            return collisionPairs;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke Java SIMD push method", e);
        }
    }

    static class JavaSIMD {
        private static final double SCALE = 64.0;
        private static final double WORLD_OFFSET = 50000000.0;
        private static final long MASK_X = 0xFFFFFFFFFL;
        private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_256;

        private int currentSize = -1;
        private long[] sortKeys, auxKeys;
        private int[] ids, auxIds;
        private int[] sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ;
        private int[] sOriginalIDs, runIndexPerItem, runStarts, collisionCounts;
        private int[] quantizedData;

        public void ensureSize(int n) {
            if (n > currentSize) {
                int alloc = Math.max(n, currentSize == -1 ? n : currentSize * 2);
                sortKeys = new long[alloc];
                auxKeys = new long[alloc];
                ids = new int[alloc];
                auxIds = new int[alloc];

                sMinX = new int[alloc];
                sMinY = new int[alloc];
                sMinZ = new int[alloc];
                sMaxX = new int[alloc];
                sMaxY = new int[alloc];
                sMaxZ = new int[alloc];

                quantizedData = new int[alloc * 6];
                sOriginalIDs = new int[alloc];
                runIndexPerItem = new int[alloc];
                runStarts = new int[alloc + 2];
                collisionCounts = new int[alloc];

                currentSize = alloc;
            }
        }

        public int push(double[] aabbs, int[] outA, int[] outB, float[] outDensity, int entityCount, int K, int gridSize, int densityWindow) {
            if (entityCount < 2) return 0;
            ensureSize(entityCount);

            double invGridSize = 1.0 / (double) gridSize;

            IntStream.range(0, entityCount).parallel().forEach(i -> {
                ids[i] = i;

                double dMinX = aabbs[i * 6 + 0];
                double dMinY = aabbs[i * 6 + 1];
                double dMinZ = aabbs[i * 6 + 2];
                double dMaxX = aabbs[i * 6 + 3];
                double dMaxY = aabbs[i * 6 + 4];
                double dMaxZ = aabbs[i * 6 + 5];

                long qX = (long) ((dMinX + WORLD_OFFSET) * SCALE);
                long gridZ = (long) ((dMinZ + WORLD_OFFSET) * invGridSize);

                long key = 0;
                key |= (gridZ << 36);
                key |= (qX & MASK_X);
                sortKeys[i] = key;

                int baseIdx = i * 6;
                quantizedData[baseIdx + 0] = (int) (dMinX * SCALE);
                quantizedData[baseIdx + 1] = (int) (dMinY * SCALE);
                quantizedData[baseIdx + 2] = (int) (dMinZ * SCALE);
                quantizedData[baseIdx + 3] = (int) (dMaxX * SCALE);
                quantizedData[baseIdx + 4] = (int) (dMaxY * SCALE);
                quantizedData[baseIdx + 5] = (int) (dMaxZ * SCALE);
            });

            radixSort(entityCount);

            int runIndex = 0;
            runStarts[0] = 0;
            long currentGrid = sortKeys[0] >>> 36;
            for (int i = 0; i < entityCount; ++i) {
                long g = sortKeys[i] >>> 36;
                if (g != currentGrid) {
                    runStarts[++runIndex] = i;
                    currentGrid = g;
                }
                runIndexPerItem[i] = runIndex;
            }
            runStarts[++runIndex] = entityCount;
            final int totalRuns = runIndex;

            IntStream.range(0, entityCount).parallel().forEach(i -> {
                int originalID = ids[i];
                sOriginalIDs[i] = originalID;

                int baseIdx = originalID * 6;
                sMinX[i] = quantizedData[baseIdx + 0];
                sMinY[i] = quantizedData[baseIdx + 1];
                sMinZ[i] = quantizedData[baseIdx + 2];
                sMaxX[i] = quantizedData[baseIdx + 3];
                sMaxY[i] = quantizedData[baseIdx + 4];
                sMaxZ[i] = quantizedData[baseIdx + 5];
            });

            if (outDensity != null) {
                final float EPSILON_DISTANCE = 0.1f;
                IntStream.range(0, totalRuns).parallel().forEach(grid -> {
                    int startIdx = runStarts[grid];
                    int endIdx = runStarts[grid + 1];
                    for (int i = startIdx; i < endIdx; ++i) {
                        int left = Math.max(startIdx, i - densityWindow);
                        int right = Math.min(endIdx - 1, i + densityWindow);
                        int count = right - left + 1;

                        if (count <= 1) {
                            outDensity[sOriginalIDs[i]] = 0.0f;
                            continue;
                        }

                        int dx_quantized = sMinX[right] - sMinX[left];
                        float dx_real = (float) dx_quantized / (float) SCALE;
                        float localDensity = (float) count / (dx_real + EPSILON_DISTANCE);
                        outDensity[sOriginalIDs[i]] = localDensity;
                    }
                });
            }

            IntStream.range(0, entityCount).parallel().forEach(i -> {
                int idA = sOriginalIDs[i];
                int maxXA = sMaxX[i], minXA = sMinX[i];
                int maxYA = sMaxY[i], minYA = sMinY[i];
                int maxZA = sMaxZ[i], minZA = sMinZ[i];

                IntVector vMaxXA = IntVector.broadcast(SPECIES, maxXA);
                IntVector vMinXA = IntVector.broadcast(SPECIES, minXA);
                IntVector vMaxYA = IntVector.broadcast(SPECIES, maxYA);
                IntVector vMinYA = IntVector.broadcast(SPECIES, minYA);
                IntVector vMaxZA = IntVector.broadcast(SPECIES, maxZA);
                IntVector vMinZA = IntVector.broadcast(SPECIES, minZA);

                int writeOffset = i * K;
                int currentCollisions = 0;

                int myRun = runIndexPerItem[i];
                int endOfMyGrid = runStarts[myRun + 1];

                if (i + 1 < endOfMyGrid) {
                    currentCollisions = processRange(i + 1, endOfMyGrid, currentCollisions, writeOffset, K,
                            maxXA, minXA, maxYA, minYA, maxZA, minZA,
                            vMaxXA, vMinXA, vMaxYA, vMinYA, vMaxZA, vMinZA, idA, outA, outB);
                }
                if (currentCollisions < K && myRun + 2 <= totalRuns) {
                    int startNext = runStarts[myRun + 1];
                    int endNext = runStarts[myRun + 2];
                    if (startNext < endNext) {
                        currentCollisions = processRange(startNext, endNext, currentCollisions, writeOffset, K,
                                maxXA, minXA, maxYA, minYA, maxZA, minZA,
                                vMaxXA, vMinXA, vMaxYA, vMinYA, vMaxZA, vMinZA, idA, outA, outB);
                    }
                }
                collisionCounts[i] = currentCollisions;
            });

            int totalCollisionCount = 0, currentOffset = 0;
            for (int i = 0; i < entityCount; ++i) {
                int count = collisionCounts[i];
                if (count > 0) {
                    int srcOffset = i * K;
                    if (srcOffset != currentOffset) {
                        System.arraycopy(outA, srcOffset, outA, currentOffset, count);
                        System.arraycopy(outB, srcOffset, outB, currentOffset, count);
                    }
                    currentOffset += count;
                    totalCollisionCount += count;
                }
            }
            return totalCollisionCount;
        }

        private int processRange(int start, int end, int currentCollisions, int writeOffset, int K,
                                 int maxXA, int minXA, int maxYA, int minYA, int maxZA, int minZA,
                                 IntVector vMaxXA, IntVector vMinXA, IntVector vMaxYA, IntVector vMinYA, IntVector vMaxZA, IntVector vMinZA,
                                 int idA, int[] outA, int[] outB) {
            int j = start;
            int limit = end - SPECIES.length();

            for (; j <= limit; j += SPECIES.length()) {
                IntVector vMinXB = IntVector.fromArray(SPECIES, sMinX, j);

                VectorMask<Integer> vIsAllGreater = vMinXB.compare(VectorOperators.GT, vMaxXA);
                if (vIsAllGreater.allTrue()) return currentCollisions;

                IntVector vMaxXB = IntVector.fromArray(SPECIES, sMaxX, j);
                VectorMask<Integer> maskX = vIsAllGreater.not().and(vMaxXB.compare(VectorOperators.GT, vMinXA));
                if (!maskX.anyTrue()) continue;

                IntVector vMinYB = IntVector.fromArray(SPECIES, sMinY, j);
                IntVector vMaxYB = IntVector.fromArray(SPECIES, sMaxY, j);
                VectorMask<Integer> maskY = vMaxYA.compare(VectorOperators.GT, vMinYB).and(vMaxYB.compare(VectorOperators.GT, vMinYA));
                VectorMask<Integer> maskXY = maskX.and(maskY);
                if (!maskXY.anyTrue()) continue;

                IntVector vMinZB = IntVector.fromArray(SPECIES, sMinZ, j);
                IntVector vMaxZB = IntVector.fromArray(SPECIES, sMaxZ, j);
                VectorMask<Integer> maskZ = vMaxZA.compare(VectorOperators.GT, vMinZB).and(vMaxZB.compare(VectorOperators.GT, vMinZA));
                VectorMask<Integer> maskXYZ = maskXY.and(maskZ);

                long laneMask = maskXYZ.toLong();

                while (laneMask != 0 && currentCollisions < K) {
                    int bitPos = Long.numberOfTrailingZeros(laneMask);
                    outA[writeOffset + currentCollisions] = idA;
                    outB[writeOffset + currentCollisions] = sOriginalIDs[j + bitPos];
                    currentCollisions++;
                    laneMask &= (laneMask - 1);
                }

                if (currentCollisions >= K) return currentCollisions;
            }

            for (; j < end; ++j) {
                if (sMinX[j] > maxXA) return currentCollisions;
                if (currentCollisions >= K) return currentCollisions;

                if (!(maxXA <= sMinX[j] || minXA >= sMaxX[j] ||
                        maxYA <= sMinY[j] || minYA >= sMaxY[j] ||
                        maxZA <= sMinZ[j] || minZA >= sMaxZ[j])) {
                    outA[writeOffset + currentCollisions] = idA;
                    outB[writeOffset + currentCollisions] = sOriginalIDs[j];
                    currentCollisions++;
                }
            }
            return currentCollisions;
        }

        private void radixSort(int n) {
            long[] srcKeys = sortKeys, dstKeys = auxKeys;
            int[] srcIds = ids, dstIds = auxIds;
            int[] count = new int[257];
            for (int pass = 0; pass < 8; ++pass) {
                int shift = pass * 8;
                Arrays.fill(count, 0);
                for (int i = 0; i < n; i++) count[((int) (srcKeys[i] >>> shift) & 0xFF) + 1]++;
                for (int i = 0; i < 256; i++) count[i + 1] += count[i];
                for (int i = 0; i < n; i++) {
                    int b = (int) (srcKeys[i] >>> shift) & 0xFF;
                    int idx = count[b]++;
                    dstKeys[idx] = srcKeys[i];
                    dstIds[idx] = srcIds[i];
                }
                long[] tmpK = srcKeys;
                srcKeys = dstKeys;
                dstKeys = tmpK;
                int[] tmpI = srcIds;
                srcIds = dstIds;
                dstIds = tmpI;
            }
        }
    }
}

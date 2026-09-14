package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.algorithm.CollisionConfig;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionEngine;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionResult;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class JNIBackend implements CollisionEngine {
    private final Path libraryPath;
    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1, 0);
    private static final AtomicLong maxSizeTouched = new AtomicLong(-1);
    private static volatile boolean isInitialized = false;
    private static boolean libraryLoaded = false;

    public JNIBackend(Path libraryPath) {
        this.libraryPath = libraryPath;
    }

    private static native long createCtx();

    private static native void destroyCtx(long ctxPtr);

    private static native long createCfg(int maxCollision, int gridSize, int densityWindow, int maxThreads);

    private static native void updateCfg(long cfgPtr, int maxCollision, int gridSize, int densityWindow, int maxThreads);

    private static native void destroyCfg(long cfgPtr);

    private static native int push(double[] aabbs, int[] outputA, int[] outputB, int count, float[] densityBuf, long ctxPtr, long cfgPtr);

    @Override
    public String getName() {
        return "JNI";
    }

    static class PushResultJNI implements CollisionResult {
        private int[] arrayA;
        private int[] arrayB;
        private float[] arrayDensity;

        private PushResultJNI() {
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

    private class ThreadState {
        int[] bufA;
        int[] bufB;
        float[] densityBuf;
        long contextPtr = 0;
        long configPtr = 0;
        int currentSize = -1;
        final PushResultJNI resultWrapper = new PushResultJNI();

        ThreadState() {
            try {
                contextPtr = createCtx();
                configPtr = createCfg(
                        config.maxCollision(),
                        config.gridSize(),
                        config.densityWindow(),
                        config.maxThreads()
                );
            } catch (Throwable e) {
                throw new RuntimeException("Failed to create JNI native context for thread", e);
            }
        }

        PushResultJNI reallocOutputBuf(int newSize) {
            int newCapacity = (int) (newSize * 1.2);
            if (newCapacity > currentSize) {
                int allocSize = Math.max(1024, newCapacity);
                bufA = new int[allocSize];
                bufB = new int[allocSize];
                densityBuf = new float[allocSize];
                currentSize = allocSize;
            }
            resultWrapper.update(bufA, bufB, densityBuf);
            return resultWrapper;
        }

        void destroy() {
            Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
            if (contextPtr != 0) {
                try {
                    destroyCtx(contextPtr);
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to destroy ctx", e);
                }
                contextPtr = 0;
            }
            if (configPtr != 0) {
                try {
                    destroyCfg(configPtr);
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to destroy cfg", e);
                }
                configPtr = 0;
            }
            bufA = null;
            bufB = null;
            densityBuf = null;
        }
    }

    private final Set<ThreadState> allThreadStates = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(() -> {
        ThreadState state = new ThreadState();
        allThreadStates.add(state);
        return state;
    });

    @Override
    public void setConfig(CollisionConfig config) {
        this.config = config;
        if (!isInitialized) return;
        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        for (ThreadState state : allThreadStates) {
            if (state.configPtr != 0) {
                try {
                    updateCfg(state.configPtr, config.maxCollision(), config.gridSize(), config.densityWindow(), config.maxThreads());
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to update JNI native config", e);
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (!isInitialized) return;
        isInitialized = false;

        for (ThreadState state : allThreadStates) {
            state.destroy();
        }
        allThreadStates.clear();
        maxSizeTouched.set(-1);
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        if (!isInitialized) return null;

        ThreadState state = threadState.get();
        if (state.contextPtr == 0) return null;

        int count = locations.length / 3;
        int resultSize = locations.length * config.maxCollision();
        maxSizeTouched.updateAndGet(current -> Math.max(current, count));

        PushResultJNI collisionPairs = state.reallocOutputBuf(resultSize);

        try {
            int collisionSize = push(
                    aabb,
                    collisionPairs.arrayA,
                    collisionPairs.arrayB,
                    count,
                    collisionPairs.arrayDensity,
                    state.contextPtr,
                    state.configPtr
            );

            resultSizeOut[0] = collisionSize;
            if (collisionSize == -1) return null;

            return collisionPairs;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke JNI push method", e);
        }
    }

    @Override
    public void initialize() {
        if (isInitialized) return;
        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        String dllPath = libraryPath.toAbsolutePath().toString();
        if (!libraryLoaded) {
            try {
                System.load(dllPath);
                libraryLoaded = true;
                logger.log(Level.INFO, "Loaded dll: {0}", dllPath);
            } catch (UnsatisfiedLinkError e) {
                throw new RuntimeException("Failed to load JNI library", e);
            }
        }
        isInitialized = true;
        logger.log(Level.INFO, "JNI acceleratedRecoiling initialized.");
    }
}

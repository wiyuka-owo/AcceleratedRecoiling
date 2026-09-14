package com.wiyuka.acceleratedrecoiling.algorithm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public class FfmCollisionEngine implements CollisionEngine {
    private final Path libraryPath;
    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1);
    private static Linker linker;
    private static Arena nativeArena;
    private static MethodHandle pushMethodHandle = null;
    private static MethodHandle createCtxMethodHandle = null;
    private static MethodHandle destroyCtxMethodHandle = null;
    private static MethodHandle createCfgMethodHandle = null;
    private static MethodHandle updateCfgMethodHandle = null;
    private static MethodHandle destroyCfgMethodHandle = null;
    private static final AtomicLong maxSizeTouched = new AtomicLong(-1);
    private static volatile boolean isInitialized = false;

    public FfmCollisionEngine(Path libraryPath) {
        this.libraryPath = libraryPath;
    }

    @Override
    public String getName() {
        return "FFM";
    }

    static class PushResultFFM implements CollisionResult {
        private MemorySegment segmentA;
        private MemorySegment segmentB;
        private MemorySegment segmentDensity;

        private PushResultFFM() {
        }

        void update(MemorySegment a, MemorySegment b, MemorySegment density) {
            this.segmentA = a;
            this.segmentB = b;
            this.segmentDensity = density;
        }

        @Override
        public int getA(int index) {
            return segmentA.get(JAVA_INT, (long) index * Integer.BYTES);
        }

        @Override
        public int getB(int index) {
            return segmentB.get(JAVA_INT, (long) index * Integer.BYTES);
        }

        @Override
        public float getDensity(int index) {
            return segmentDensity.get(JAVA_FLOAT, (long) index * Float.BYTES);
        }

        @Override
        public void copyATo(int[] dest, int length) {
            MemorySegment.copy(segmentA, JAVA_INT, 0, dest, 0, length);
        }

        @Override
        public void copyBTo(int[] dest, int length) {
            MemorySegment.copy(segmentB, JAVA_INT, 0, dest, 0, length);
        }

        @Override
        public void copyDensityTo(float[] dest, int length) {
            MemorySegment.copy(segmentDensity, JAVA_FLOAT, 0, dest, 0, length);
        }
    }

    private class ThreadState {
        Arena bufferArena = null;
        MemorySegment bufA;
        MemorySegment bufB;
        MemorySegment densityBuf;
        MemorySegment context;
        MemorySegment configPtr;
        int currentSize = -1;
        final PushResultFFM resultWrapper = new PushResultFFM();

        ThreadState() {
            try {
                if (createCtxMethodHandle != null) {
                    context = (MemorySegment) createCtxMethodHandle.invokeExact();
                }
                if (createCfgMethodHandle != null) {
                    configPtr = (MemorySegment) createCfgMethodHandle.invokeExact(
                            config.maxCollision(),
                            config.gridSize(),
                            config.densityWindow(),
                            config.maxThreads()
                    );
                }
            } catch (Throwable e) {
                throw new RuntimeException("Failed to create native context for thread", e);
            }
        }

        PushResultFFM reallocOutputBuf(int newSize) {
            int newCapacity = (int) (newSize * 1.2);
            long newSizeTotal = Math.max(1024L, (long) newCapacity * JAVA_INT.byteSize());
            long densitySizeTotal = Math.max(1024L, (long) newCapacity * JAVA_FLOAT.byteSize());
            if (newSizeTotal > currentSize) {
                if (bufferArena != null) {
                    bufferArena.close();
                }
                bufferArena = Arena.ofConfined();
                bufA = bufferArena.allocate(newSizeTotal);
                bufB = bufferArena.allocate(newSizeTotal);
                densityBuf = bufferArena.allocate(densitySizeTotal);
                currentSize = (int) newSizeTotal;
            }
            resultWrapper.update(bufA, bufB, densityBuf);
            return resultWrapper;
        }

        void destroy() {
            Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
            if (bufferArena != null) {
                try {
                    bufferArena.close();
                } catch (Exception ignored) {
                }
            }
            if (context != null && destroyCtxMethodHandle != null) {
                try {
                    destroyCtxMethodHandle.invokeExact(context);
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to destroy native context", e);
                }
            }
            if (configPtr != null && destroyCfgMethodHandle != null) {
                try {
                    destroyCfgMethodHandle.invokeExact(configPtr);
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to destroy native config", e);
                }
            }
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
        if (!isInitialized || updateCfgMethodHandle == null) {
            return;
        }
        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        for (ThreadState state : allThreadStates) {
            if (state.configPtr != null) {
                try {
                    updateCfgMethodHandle.invokeExact(
                            state.configPtr,
                            config.maxCollision(),
                            config.gridSize(),
                            config.densityWindow(),
                            config.maxThreads()
                    );
                } catch (Throwable e) {
                    logger.log(Level.ERROR, "Failed to update native config for thread", e);
                }
            }
        }
    }

    @Override
    public void destroy() {
        if (!isInitialized) {
            return;
        }
        isInitialized = false;

        for (ThreadState state : allThreadStates) {
            state.destroy();
        }
        allThreadStates.clear();

        nativeArena = null;
        linker = null;
        pushMethodHandle = null;
        createCtxMethodHandle = null;
        destroyCtxMethodHandle = null;
        createCfgMethodHandle = null;
        updateCfgMethodHandle = null;
        destroyCfgMethodHandle = null;

        maxSizeTouched.set(-1);
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        if (!isInitialized) {
            return null;
        }

        ThreadState state = threadState.get();
        if (state.context == null) {
            return null;
        }

        try (Arena tempArena = Arena.ofConfined()) {
            int count = locations.length / 3;
            int resultSize = locations.length * config.maxCollision();
            maxSizeTouched.updateAndGet(current -> Math.max(current, count));

            MemorySegment aabbMem = FfmAlloc.allocateArray(tempArena, aabb);
            PushResultFFM collisionPairs = state.reallocOutputBuf(resultSize);

            int collisionSize;
            try {
                collisionSize = (int) pushMethodHandle.invokeExact(
                        aabbMem,
                        collisionPairs.segmentA,
                        collisionPairs.segmentB,
                        count,
                        collisionPairs.segmentDensity,
                        state.context,
                        state.configPtr
                );
            } catch (Throwable e) {
                throw new RuntimeException("Failed to invoke native push method", e);
            }

            resultSizeOut[0] = collisionSize;
            if (collisionSize == -1) return null;

            return collisionPairs;
        }
    }

    @Override
    public void initialize() {
        if (isInitialized) return;

        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        String dllPath = libraryPath.toAbsolutePath().toString();

        linker = Linker.nativeLinker();
        nativeArena = Arena.global();
        SymbolLookup lib = SymbolLookup.libraryLookup(dllPath, nativeArena);
        pushMethodHandle = linker.downcallHandle(
                lib.find("push").orElseThrow(() -> new RuntimeException("Cannot find symbol 'push'")),
                FunctionDescriptor.of(
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS
                )
        );
        createCtxMethodHandle = linker.downcallHandle(
                lib.find("createCtx").orElseThrow(() -> new RuntimeException("Cannot find symbol 'createCtx'")),
                FunctionDescriptor.of(ADDRESS)
        );
        createCfgMethodHandle = linker.downcallHandle(
                lib.find("createCfg").orElseThrow(() -> new RuntimeException("Cannot find symbol 'createCfg'")),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
        );
        try {
            updateCfgMethodHandle = linker.downcallHandle(
                    lib.find("updateCfg").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
            );
        } catch (Exception e) {
            logger.log(Level.WARNING, "Cannot find symbol 'updateCfg'");
        }
        try {
            destroyCfgMethodHandle = linker.downcallHandle(
                    lib.find("destroyCfg").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS)
            );
        } catch (Exception e) {
            logger.log(Level.WARNING, "Cannot find symbol 'destroyCfg'");
        }
        try {
            destroyCtxMethodHandle = linker.downcallHandle(
                    lib.find("destroyCtx").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS)
            );
        } catch (Exception e) {
            logger.log(Level.WARNING, "Cannot find symbol 'destroyCtx'");
        }
        isInitialized = true;
    }
}

package com.wiyuka.acceleratedrecoiling.algorithm;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class FfmAlloc {
    private static final MethodHandle ALLOCATE_HANDLE;

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle handle;
        int version;
        try {
            handle = lookup.findVirtual(Arena.class, "allocateFrom",
                    MethodType.methodType(MemorySegment.class, ValueLayout.OfDouble.class, double[].class));
            version = 22;
        } catch (NoSuchMethodException | IllegalAccessException e1) {
            try {
                handle = lookup.findVirtual(Arena.class, "allocateArray",
                        MethodType.methodType(MemorySegment.class, ValueLayout.OfDouble.class, double[].class));
                version = 21;
            } catch (NoSuchMethodException | IllegalAccessException e2) {
                throw new RuntimeException("FFM Initialization Failed: Compatible 'allocate' method not found. " +
                        "Require JDK 21 (allocateArray) or JDK 22+ (allocateFrom).", e2);
            }
        }
        ALLOCATE_HANDLE = handle;
        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        logger.log(Level.INFO, "[FFM] Native Linker initialized. Detected JDK Compatibility Level: {0}", version);
    }

    private FfmAlloc() {
    }

    static MemorySegment allocateArray(Arena arena, double[] array) {
        try {
            return (MemorySegment) ALLOCATE_HANDLE.invokeExact(arena, ValueLayout.JAVA_DOUBLE, array);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to allocate native array", e);
        }
    }
}

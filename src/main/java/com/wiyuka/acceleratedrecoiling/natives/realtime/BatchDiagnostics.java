package com.wiyuka.acceleratedrecoiling.natives.realtime;

public final class BatchDiagnostics {
    public static final boolean ENABLED = Boolean.getBoolean("ar.batchDiagnostics");
    public static final boolean TIMING = Boolean.getBoolean("ar.batchTiming");
    public static long attempts, sourceRejected, rebuilds, allocatedBytes, preparedEntities, coldStates;
    public static long prepareNanos, nativeNanos, dispatchNanos;

    private BatchDiagnostics() {
    }
}

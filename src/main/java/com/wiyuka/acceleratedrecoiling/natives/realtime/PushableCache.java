package com.wiyuka.acceleratedrecoiling.natives.realtime;

public final class PushableCache {
    public static long holds;
    public static long misses;
    public static long epochMisses;

    private PushableCache() {
    }

    public static void reset() {
        holds = 0;
        misses = 0;
        epochMisses = 0;
    }
}
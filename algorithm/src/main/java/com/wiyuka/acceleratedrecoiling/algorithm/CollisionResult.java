package com.wiyuka.acceleratedrecoiling.algorithm;

public interface CollisionResult {
    int getA(int index);

    int getB(int index);

    float getDensity(int index);

    void copyATo(int[] dest, int length);

    void copyBTo(int[] dest, int length);

    void copyDensityTo(float[] dest, int length);
}

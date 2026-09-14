package com.wiyuka.acceleratedrecoiling.algorithm;

public interface CollisionEngine {
    void initialize();

    void setConfig(CollisionConfig config);

    void destroy();

    String getName();

    CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut);
}

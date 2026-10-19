package com.wiyuka.acceleratedrecoiling.natives.realtime;

public enum CollisionResult {
    VANILLA(-2),   // 回退到原版
    CALLBACKS(-1), // 由快速路径筛选，由MC执行碰撞
    REJECTED(0),   // 不参与碰撞
    ACCEPTED(1);   // 全流程由快速路径计算

    public final int nativeFlag;

    CollisionResult(int nativeFlag) {
        this.nativeFlag = nativeFlag;
    }
}

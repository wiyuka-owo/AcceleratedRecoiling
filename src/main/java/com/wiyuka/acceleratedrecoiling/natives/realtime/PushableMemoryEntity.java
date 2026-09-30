package com.wiyuka.acceleratedrecoiling.natives.realtime;

public interface PushableMemoryEntity {
    void ar$invalidatePushable();

    boolean ar$isPushableInTickingSection();
}

package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

public interface VersionedBlockSection {
    long ar$blockVersion();

    SectionBlockIndex ar$blockIndex();

    void ar$blockIndex(SectionBlockIndex index);
}

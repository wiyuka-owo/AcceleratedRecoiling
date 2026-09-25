package com.wiyuka.acceleratedrecoiling.natives.realtime;

import net.minecraft.world.level.block.state.BlockState;

public interface IndexedEntity {

    void ar$bindSection(RealtimeSection section, int slot);

    void ar$unbindSection(RealtimeSection section);

    void ar$collisionStateDirty();

    BlockState ar$cachedBlockState();

    RealtimeSection ar$section();

    int ar$sectionSlot();
}

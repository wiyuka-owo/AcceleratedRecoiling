package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.SectionBlockIndex;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.VersionedBlockSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
abstract class BlockSectionVersionMixin implements VersionedBlockSection {
    @Unique
    private long ar$blockVersion;

    @Unique
    private SectionBlockIndex ar$blockIndex;

    @Override
    public SectionBlockIndex ar$blockIndex() {
        return ar$blockIndex;
    }

    @Override
    public void ar$blockIndex(SectionBlockIndex index) {
        ar$blockIndex = index;
    }

    @Override
    public long ar$blockVersion() {
        return ar$blockVersion;
    }

    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = { @At("HEAD"), @At("RETURN") })
    private void ar$blockChanged(int x, int y, int z, BlockState state, boolean lock,
            CallbackInfoReturnable<BlockState> ci) {
        ar$blockVersion++;
        if (ar$blockIndex != null) {
            ar$blockIndex.markDirty(x, y, z);
        }
    }

    @Inject(method = { "read", "recalcBlockCounts" }, at = { @At("HEAD"), @At("RETURN") })
    private void ar$blocksReplaced(CallbackInfo ci) {
        ar$blockVersion++;
        ar$blockIndex = null;
    }
}

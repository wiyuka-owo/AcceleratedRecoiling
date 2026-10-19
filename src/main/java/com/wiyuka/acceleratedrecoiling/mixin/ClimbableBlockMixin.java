package com.wiyuka.acceleratedrecoiling.mixin;

import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
abstract class ClimbableBlockMixin {
    @Inject(method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void ar$ladderChanged(int x, int y, int z, BlockState state, boolean lock,
            CallbackInfoReturnable<BlockState> ci) {
        BlockState previous = ci.getReturnValue();
        if (previous != state && (previous.is(Blocks.LADDER) || state.is(Blocks.LADDER))) {
            BatchedRules.invalidatePolicy();
        }
    }

    @Inject(method = { "read", "recalcBlockCounts" }, at = { @At("HEAD"), @At("RETURN") })
    private void ar$blocksReplaced(CallbackInfo ci) {
        BatchedRules.invalidatePolicy();
    }
}

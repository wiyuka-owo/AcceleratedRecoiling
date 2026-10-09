package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.Shapes;

public final class SectionBlockIndex {
    private static final boolean BARRIER_SUPPORTED = BatchedRules.barrierBlockCollisions();

    private final BitSet dirtyBlocks = new BitSet(4096);
    private final int[] fullRows = new int[256];
    private final int[] specialRows = new int[256];
    private final int[] largeRows = new int[256];
    private final int[] pistonRows = new int[256];

    private SectionBlockIndex() {
        dirtyBlocks.set(0, 4096);
    }

    static SectionBlockIndex get(LevelChunkSection section, Level level, int baseX, int baseY, int baseZ) {
        var storage = (VersionedBlockSection) section;
        var index = storage.ar$blockIndex();
        if (index == null) {
            index = new SectionBlockIndex();
            storage.ar$blockIndex(index);
        }
        index.refresh(section, level, baseX, baseY, baseZ);
        return index;
    }

    public void markDirty(int x, int y, int z) {
        dirtyBlocks.set((z * 16 + y) * 16 + x);
    }

    private void refresh(LevelChunkSection section, Level level, int baseX, int baseY, int baseZ) {
        if (dirtyBlocks.isEmpty()) {
            return;
        }

        var position = new BlockPos.MutableBlockPos();
        for (int blockIndex = dirtyBlocks.nextSetBit(0); blockIndex >= 0;
                blockIndex = dirtyBlocks.nextSetBit(blockIndex + 1)) {
            int x = blockIndex & 15;
            int y = (blockIndex >> 4) & 15;
            int z = blockIndex >> 8;
            var state = section.getBlockState(x, y, z);
            position.set(baseX + x, baseY + y, baseZ + z);
            updateBlock(state, level, position, blockIndex >> 4, 1 << x);
        }
        dirtyBlocks.clear();
    }

    private void updateBlock(BlockState state, Level level, BlockPos position, int row, int bit) {
        fullRows[row] &= ~bit;
        specialRows[row] &= ~bit;
        largeRows[row] &= ~bit;
        pistonRows[row] &= ~bit;

        if (state.hasLargeCollisionShape()) {
            largeRows[row] |= bit;
        }
        if (state.is(Blocks.MOVING_PISTON)) {
            pistonRows[row] |= bit;
        }

        var block = state.getBlock();
        var type = block.getClass();
        boolean supported = type == Block.class || type == AirBlock.class
                || (type == BarrierBlock.class && BARRIER_SUPPORTED);

        if (!supported || block.hasDynamicShape() || state.hasOffsetFunction()) {
            specialRows[row] |= bit;
            return;
        }

        var shape = state.getCollisionShape(level, position);
        if (shape == Shapes.block()) {
            fullRows[row] |= bit;
        } else if (!shape.isEmpty()) {
            specialRows[row] |= bit;
        }
    }

    int row(int y, int z, int interiorMask, int edgeMask, int borderAxes) {
        int row = z * 16 + y;
        int candidates = switch (borderAxes) {
            case 0 -> interiorMask | (edgeMask & largeRows[row]);
            case 1 -> (interiorMask & largeRows[row]) | (edgeMask & pistonRows[row]);
            default -> interiorMask & pistonRows[row];
        };

        if ((specialRows[row] & candidates) != 0) {
            return -1;
        }
        return fullRows[row] & candidates;
    }
}

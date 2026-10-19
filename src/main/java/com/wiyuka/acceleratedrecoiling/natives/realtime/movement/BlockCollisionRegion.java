package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BlockCollisionRegion {
    static final int ROW_SHIFT = 3;
    static final int EDGE = 1 << ROW_SHIFT;
    private static final int LAYER_SHIFT = ROW_SHIFT * 2;

    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;

    private final ChunkSnapshot[] chunks;
    private final long[] occupiedLayers = new long[EDGE];
    boolean fullBlocksOnly = true;

    BlockCollisionRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;

        int chunkCount = ((maxX >> 4) - (minX >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
        chunks = new ChunkSnapshot[chunkCount];
    }

    ChunkSnapshot captureChunk(int index, int chunkX, int chunkZ) {
        int firstSectionY = minY >> 4;
        int sectionCount = (maxY >> 4) - firstSectionY + 1;
        var snapshot = new ChunkSnapshot(chunkX, chunkZ, firstSectionY,
                new LevelChunkSection[sectionCount], new long[sectionCount]);
        chunks[index] = snapshot;
        return snapshot;
    }

    boolean canReuse(Level level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return this.minX == minX && this.minY == minY && this.minZ == minZ
                && this.maxX == maxX && this.maxY == maxY && this.maxZ == maxZ
                && isUnchanged(level);
    }

    boolean isUnchanged(Level level) {
        for (var chunk : chunks) {
            if (!chunk.isUnchanged(level)) {
                return false;
            }
        }
        return true;
    }

    record ChunkSnapshot(int chunkX, int chunkZ, int firstSectionY,
                         LevelChunkSection[] sections, long[] versions) {
        void captureSection(int sectionY, LevelChunkSection section) {
            int index = sectionY - firstSectionY;
            sections[index] = section;
            versions[index] = ((VersionedBlockSection) section).ar$blockVersion();
        }

        boolean isUnchanged(Level level) {
            var getter = level.getChunkForCollisions(chunkX, chunkZ);
            if (getter == null || getter.getClass() != LevelChunk.class) {
                return false;
            }

            var chunk = (LevelChunk) getter;
            int firstSectionIndex = chunk.getSectionIndex(firstSectionY << 4);
            for (int index = 0; index < sections.length; index++) {
                var section = chunk.getSection(firstSectionIndex + index);
                if (section != sections[index]
                        || ((VersionedBlockSection) section).ar$blockVersion() != versions[index]) {
                    return false;
                }
            }
            return true;
        }
    }

    boolean isCandidate(int x, int y, int z) {
        int bitIndex = (y - minY) * EDGE + x - minX;
        return (occupiedLayers[z - minZ] & (1L << bitIndex)) != 0;
    }

    void addRow(int y, int z, int rowBits) {
        int localY = y - minY;
        int localZ = z - minZ;

        int bitOffset = localY * EDGE;
        occupiedLayers[localZ] |= (long) rowBits << bitOffset;
    }

    FullBlockCollisions intersect(AABB area) {
        long[] intersectingLayers = null;
        int collisionCount = 0;

        for (int localZ = 0; localZ < EDGE; localZ++) {
            int blockZ = minZ + localZ;
            boolean overlapsZ = area.minZ < blockZ + 1.0 && area.maxZ > blockZ;
            if (!overlapsZ) {
                continue;
            }

            long remainingBlocks = occupiedLayers[localZ];
            while (remainingBlocks != 0) {
                int bitIndex = Long.numberOfTrailingZeros(remainingBlocks);
                remainingBlocks &= remainingBlocks - 1;

                int localX = bitIndex & (EDGE - 1);
                int localY = bitIndex >> ROW_SHIFT;
                int blockX = minX + localX;
                int blockY = minY + localY;

                if (!(area.minX < blockX + 1.0 && area.maxX > blockX)) {
                    continue;
                }

                if (!(area.minY < blockY + 1.0 && area.maxY > blockY)) {
                    continue;
                }

                if (intersectingLayers == null) {
                    intersectingLayers = new long[EDGE];
                }

                intersectingLayers[localZ] |= 1L << bitIndex;
                collisionCount++;
            }
        }

        if (intersectingLayers == null) {
            return FullBlockCollisions.EMPTY;
        }
        return new FullBlockCollisions(this, intersectingLayers, collisionCount);
    }

    BlockPos position(int cellIndex) {
        int localX = cellIndex & (EDGE - 1);
        int localY = (cellIndex >> ROW_SHIFT) & (EDGE - 1);
        int localZ = cellIndex >> LAYER_SHIFT;

        return new BlockPos(minX + localX, minY + localY, minZ + localZ);
    }

    VoxelShape shape(int cellIndex) {
        BlockPos position = position(cellIndex);
        return Shapes.block().move(position.getX(), position.getY(), position.getZ());
    }
}

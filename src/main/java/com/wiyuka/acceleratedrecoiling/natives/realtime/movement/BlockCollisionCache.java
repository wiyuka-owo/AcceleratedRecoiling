package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

public final class BlockCollisionCache {
    private static final int CACHE_SIZE = 128;
    private static final boolean SUPPORTED = BatchedRules.simpleBlockCollisions();
    private static final boolean FULL_BLOCK_MOVEMENT = Boolean.parseBoolean(
            System.getProperty("ar.experimental.fullBlockMovement", "true")) && BatchedRules.fullBlockMovement();
    private static final ThreadLocal<CachedRegion[]> CACHE = ThreadLocal.withInitial(() -> new CachedRegion[CACHE_SIZE]);

    private record SectionSnapshot(
            int sectionY,
            LevelChunkSection section,
            long version) {

        boolean isUnchanged(LevelChunk chunk) {
            int sectionIndex = chunk.getSectionIndex(sectionY << 4);
            var currentSection = chunk.getSection(sectionIndex);
            if (currentSection != section) {
                return false;
            }

            long currentVersion = ((VersionedBlockSection) section).ar$blockVersion();
            return currentVersion == version;
        }
    }

    private record ChunkSnapshot(int chunkX, int chunkZ, SectionSnapshot[] sections) {

        boolean isUnchanged(Level level) {
            var currentChunk = level.getChunkForCollisions(chunkX, chunkZ);
            if (currentChunk == null || currentChunk.getClass() != LevelChunk.class) {
                return false;
            }

            var chunk = (LevelChunk) currentChunk;
            for (var section : sections) {
                if (!section.isUnchanged(chunk)) {
                    return false;
                }
            }

            return true;
        }
    }

    private record CachedRegion(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            ChunkSnapshot[] chunks,
            FullBlockGrid blocks) {

        boolean canReuse(Level level,
                int queryMinX, int queryMinY, int queryMinZ,
                int queryMaxX, int queryMaxY, int queryMaxZ) {
            if (!matchesBounds(queryMinX, queryMinY, queryMinZ, queryMaxX, queryMaxY, queryMaxZ)) {
                return false;
            }

            for (var chunk : chunks) {
                if (!chunk.isUnchanged(level)) {
                    return false;
                }
            }

            return true;
        }

        private boolean matchesBounds(
                int queryMinX, int queryMinY, int queryMinZ,
                int queryMaxX, int queryMaxY, int queryMaxZ) {
            return minX == queryMinX && maxX == queryMaxX
                    && minY == queryMinY && maxY == queryMaxY
                    && minZ == queryMinZ && maxZ == queryMaxZ;
        }
    }

    private BlockCollisionCache() {
    }

    public static void clear() {
        CACHE.remove();
    }

    public static FullBlockCollisions collect(Entity source, Level level, AABB area) {
        if (!canCache(source, level)) {
            return null;
        }

        int minX = Mth.floor(area.minX - 1.0E-7) - 1;
        int minY = Mth.floor(area.minY - 1.0E-7) - 1;
        int minZ = Mth.floor(area.minZ - 1.0E-7) - 1;
        int maxX = Mth.floor(area.maxX + 1.0E-7) + 1;
        int maxY = Mth.floor(area.maxY + 1.0E-7) + 1;
        int maxZ = Mth.floor(area.maxZ + 1.0E-7) + 1;

        if (minX > maxX || minY > maxY || minZ > maxZ
                || (long) maxX - minX >= FullBlockGrid.EDGE
                || (long) maxY - minY >= FullBlockGrid.EDGE
                || (long) maxZ - minZ >= FullBlockGrid.EDGE
                || level.isOutsideBuildHeight(minY) || level.isOutsideBuildHeight(maxY)) {
            return null;
        }

        var cache = CACHE.get();
        int slot = hash128(minX, minY, minZ, maxX, maxY, maxZ);

        var cachedRegion = cache[slot];
        if (cachedRegion == null || !cachedRegion.canReuse(level, minX, minY, minZ, maxX, maxY, maxZ)) {
            cachedRegion = buildRegion(level, minX, minY, minZ, maxX, maxY, maxZ);
            if (cachedRegion == null) {
                return null;
            }

            cache[slot] = cachedRegion;
        }

        if (cachedRegion.blocks == null) {
            return null;
        }
        return cachedRegion.blocks.intersect(area);
    }

    public static FullBlockCollisions fullBlocks(Entity source, Level level, AABB area) {
        return FULL_BLOCK_MOVEMENT ? collect(source, level, area) : null;
    }

    public static boolean fullBlockMovementEnabled() {
        return FULL_BLOCK_MOVEMENT;
    }

    private static boolean canCache(Entity source, Level level) {
        return RealtimeNative.isEnabled() && source != null
                && level.getClass() == ServerLevel.class && SUPPORTED
                && BatchedRules.plain(source.getClass()) && level.getServer().isSameThread();
    }

    private static int hash128(
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        int hash = minX;
        hash = hash * 31 + minY;
        hash = hash * 31 + minZ;
        hash = hash * 31 + maxX;
        hash = hash * 31 + maxY;
        hash = hash * 31 + maxZ;

        return (hash ^ (hash >>> 16)) & (CACHE_SIZE - 1);
    }

    private static CachedRegion buildRegion(Level level,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int minChunkX = minX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkX = maxX >> 4;
        int maxChunkZ = maxZ >> 4;
        int minSectionY = minY >> 4;
        int maxSectionY = maxY >> 4;

        var chunks = new ChunkSnapshot[(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
        var blocks = new FullBlockGrid(minX, minY, minZ);
        int index = 0;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                var getter = level.getChunkForCollisions(chunkX, chunkZ);
                if (getter == null || getter.getClass() != LevelChunk.class) {
                    return null;
                }

                var chunk = (LevelChunk) getter;
                var sections = new SectionSnapshot[maxSectionY - minSectionY + 1];

                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    var section = chunk.getSection(chunk.getSectionIndex(sectionY << 4));
                    if (section.getClass() != LevelChunkSection.class) {
                        return null;
                    }

                    long version = ((VersionedBlockSection) section).ar$blockVersion();
                    sections[sectionY - minSectionY] = new SectionSnapshot(sectionY, section, version);

                    if (blocks != null) {
                        var blockIndex = SectionBlockIndex.get(section, level, chunkX << 4, sectionY << 4, chunkZ << 4);
                        if (!copySectionRows(blockIndex, blocks, chunkX << 4, sectionY << 4, chunkZ << 4,
                                maxX, maxY, maxZ)) {
                            blocks = null;
                        }
                    }
                }

                chunks[index++] = new ChunkSnapshot(chunkX, chunkZ, sections);
            }
        }

        return new CachedRegion(minX, minY, minZ, maxX, maxY, maxZ, chunks, blocks);
    }

    private static boolean copySectionRows(SectionBlockIndex blockIndex, FullBlockGrid blocks,
            int baseX, int baseY, int baseZ, int maxX, int maxY, int maxZ) {
        int firstX = Math.max(blocks.minX, baseX);
        int lastX = Math.min(maxX, baseX + 15);
        int firstY = Math.max(blocks.minY, baseY);
        int lastY = Math.min(maxY, baseY + 15);
        int firstZ = Math.max(blocks.minZ, baseZ);
        int lastZ = Math.min(maxZ, baseZ + 15);

        int width = lastX - firstX + 1;
        int rangeMask = ((1 << width) - 1) << (firstX - baseX);
        int edgeMask = 0;
        if (firstX == blocks.minX) {
            edgeMask |= 1 << (firstX - baseX);
        }
        if (lastX == maxX) {
            edgeMask |= 1 << (lastX - baseX);
        }

        for (int z = firstZ; z <= lastZ; z++) {
            for (int y = firstY; y <= lastY; y++) {
                int borderAxes = 0;
                if (y == blocks.minY || y == maxY) {
                    borderAxes++;
                }
                if (z == blocks.minZ || z == maxZ) {
                    borderAxes++;
                }

                int occupied = blockIndex.row(y & 15, z & 15, rangeMask & ~edgeMask, edgeMask, borderAxes);
                if (occupied < 0) {
                    return false;
                }

                int rowBits = (occupied >>> (firstX - baseX)) << (firstX - blocks.minX);
                blocks.addRow(y, z, rowBits);
            }
        }

        return true;
    }
}

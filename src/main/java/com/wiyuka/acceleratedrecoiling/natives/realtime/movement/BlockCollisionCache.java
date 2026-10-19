package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import net.minecraft.core.Cursor3D;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

public final class BlockCollisionCache {
    private static final int CACHE_SIZE = 128;
    private static final boolean FULL_BLOCK_MOVEMENT = Boolean.parseBoolean(
            System.getProperty("ar.experimental.fullBlockMovement", "true"));
    private static final ThreadLocal<BlockCollisionRegion[]> CACHE =
            ThreadLocal.withInitial(() -> new BlockCollisionRegion[CACHE_SIZE]);

    private BlockCollisionCache() {
    }

    public static void clear() {
        CACHE.remove();
    }

    public static FullBlockCollisions collect(Entity source, Level level, AABB area) {
        if (!canCache(source, level)) {
            return null;
        }

        var region = region(level, area);
        if (region == null || !region.fullBlocksOnly) {
            return null;
        }
        return region.intersect(area);
    }

    public static Cursor3D cursor(CollisionGetter getter, AABB area, boolean onlySuffocating) {
        if (onlySuffocating || !RealtimeNative.isEnabled() || getter.getClass() != ServerLevel.class) {
            return null;
        }
        var level = (ServerLevel) getter;
        if (!level.getServer().isSameThread()) {
            return null;
        }

        var region = region(level, area);
        return region == null ? null : new BlockCollisionCursor(level, area, region);
    }

    static BlockCollisionRegion region(Level level, AABB area) {
        int minX = Mth.floor(area.minX - 1.0E-7) - 1;
        int minY = Mth.floor(area.minY - 1.0E-7) - 1;
        int minZ = Mth.floor(area.minZ - 1.0E-7) - 1;
        int maxX = Mth.floor(area.maxX + 1.0E-7) + 1;
        int maxY = Mth.floor(area.maxY + 1.0E-7) + 1;
        int maxZ = Mth.floor(area.maxZ + 1.0E-7) + 1;

        if (minX > maxX || minY > maxY || minZ > maxZ
                || (long) maxX - minX >= BlockCollisionRegion.EDGE
                || (long) maxY - minY >= BlockCollisionRegion.EDGE
                || (long) maxZ - minZ >= BlockCollisionRegion.EDGE
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

        return cachedRegion;
    }

    public static FullBlockCollisions fullBlocks(Entity source, Level level, AABB area) {
        return FULL_BLOCK_MOVEMENT ? collect(source, level, area) : null;
    }

    public static boolean fullBlockMovementEnabled() {
        return FULL_BLOCK_MOVEMENT;
    }

    private static boolean canCache(Entity source, Level level) {
        return RealtimeNative.isEnabled() && source != null
                && level.getClass() == ServerLevel.class
                && level.getServer().isSameThread()
                && (BatchedRules.plain(source.getClass()) || source.getClass() == ExperienceOrb.class
                        || source.getClass() == ItemEntity.class);
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

    private static BlockCollisionRegion buildRegion(Level level,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int minChunkX = minX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkX = maxX >> 4;
        int maxChunkZ = maxZ >> 4;
        int minSectionY = minY >> 4;
        int maxSectionY = maxY >> 4;

        var region = new BlockCollisionRegion(minX, minY, minZ, maxX, maxY, maxZ);
        int index = 0;

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                var getter = level.getChunkForCollisions(chunkX, chunkZ);
                if (getter == null || getter.getClass() != LevelChunk.class) {
                    return null;
                }

                var chunk = (LevelChunk) getter;
                var snapshot = region.captureChunk(index++, chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    var section = chunk.getSection(chunk.getSectionIndex(sectionY << 4));
                    if (section.getClass() != LevelChunkSection.class) {
                        return null;
                    }

                    snapshot.captureSection(sectionY, section);

                    var blockIndex = SectionBlockIndex.get(section, level, chunkX << 4, sectionY << 4, chunkZ << 4);
                    copySectionRows(blockIndex, region, chunkX << 4, sectionY << 4, chunkZ << 4);
                }
            }
        }

        return region;
    }

    private static void copySectionRows(SectionBlockIndex blockIndex, BlockCollisionRegion region,
            int baseX, int baseY, int baseZ) {
        int firstX = Math.max(region.minX, baseX);
        int lastX = Math.min(region.maxX, baseX + 15);
        int firstY = Math.max(region.minY, baseY);
        int lastY = Math.min(region.maxY, baseY + 15);
        int firstZ = Math.max(region.minZ, baseZ);
        int lastZ = Math.min(region.maxZ, baseZ + 15);

        int width = lastX - firstX + 1;
        int rangeMask = ((1 << width) - 1) << (firstX - baseX);
        int edgeMask = 0;
        if (firstX == region.minX) {
            edgeMask |= 1 << (firstX - baseX);
        }
        if (lastX == region.maxX) {
            edgeMask |= 1 << (lastX - baseX);
        }

        for (int z = firstZ; z <= lastZ; z++) {
            for (int y = firstY; y <= lastY; y++) {
                int borderAxes = 0;
                if (y == region.minY || y == region.maxY) {
                    borderAxes++;
                }
                if (z == region.minZ || z == region.maxZ) {
                    borderAxes++;
                }

                int candidates = blockIndex.candidates(y & 15, z & 15, rangeMask & ~edgeMask, edgeMask, borderAxes);
                int rowBits = (candidates >>> (firstX - baseX)) << (firstX - region.minX);
                region.addRow(y, z, rowBits);

                if (blockIndex.hasSpecial(y & 15, z & 15, candidates)) {
                    region.fullBlocksOnly = false;
                }
            }
        }
    }
}

package com.wiyuka.acceleratedrecoiling.natives.realtime.movement;

import net.minecraft.core.Cursor3D;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

final class BlockCollisionCursor extends Cursor3D {
    private final Level level;
    private final AABB area;
    private BlockCollisionRegion region;

    BlockCollisionCursor(Level level, AABB area, BlockCollisionRegion region) {
        super(region.minX, region.minY, region.minZ, region.maxX, region.maxY, region.maxZ);
        this.level = level;
        this.area = area;
        this.region = region;
    }

    @Override
    public boolean advance() {
        if (region != null && !region.isUnchanged(level)) {
            region = BlockCollisionCache.region(level, area);
        }

        while (super.advance()) {
            if (region == null || region.isCandidate(nextX(), nextY(), nextZ())) {
                return true;
            }
        }
        return false;
    }
}

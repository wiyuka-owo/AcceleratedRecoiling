package com.wiyuka.acceleratedrecoiling.natives.realtime.index;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class PositionGroups {
    private static final int MIN_GROUP_SIZE = 32;

    private static final class PositionGroup {
        double x;
        double z;
        int memberCount;
        PositionGroup next;
        ByteBuffer memberBitmap;
        long memberBitmapAddress;
    }

    private final PositionGroup[] entityGroups;
    private final Long2ObjectOpenHashMap<PositionGroup> groupsByHash = new Long2ObjectOpenHashMap<>();
    private final int bytesPerBitmap;
    private final int bitmapBudget;
    private PositionGroup firstReusableGroup;
    private int allocatedBitmapBytes;

    PositionGroups(int capacity, int bitmapBudget) {
        entityGroups = new PositionGroup[capacity];
        int wordsPerBitmap = Math.addExact(capacity, Long.SIZE - 1) / Long.SIZE;
        bytesPerBitmap = Math.multiplyExact(wordsPerBitmap, Long.BYTES);
        this.bitmapBudget = bitmapBudget;
    }

    long coincidentAddress(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            return 0;
        }

        PositionGroup group = findGroup(x, z);
        if (group == null || group.memberCount < MIN_GROUP_SIZE) {
            return 0;
        }

        return group.memberBitmapAddress;
    }

    void update(int slot, double x, double z, boolean eligible) {
        PositionGroup currentGroup = entityGroups[slot];
        if (eligible && currentGroup != null && currentGroup.x == x && currentGroup.z == z) {
            return;
        }

        if (currentGroup != null) {
            leaveGroup(slot, currentGroup);
        }
        if (eligible) {
            joinGroup(slot, x, z);
        }
    }

    void remove(int slot) {
        PositionGroup group = entityGroups[slot];
        if (group != null) {
            leaveGroup(slot, group);
        }
    }

    private void joinGroup(int slot, double x, double z) {
        PositionGroup group = findGroup(x, z);
        if (group == null) {
            group = createGroup(x, z);
        }

        entityGroups[slot] = group;
        group.memberCount++;

        if (group.memberBitmap != null) {
            setGroupMember(group, slot, true);
        } else if (group.memberCount == MIN_GROUP_SIZE) {
            allocateGroupBitmap(group);
        }
    }

    private PositionGroup createGroup(double x, double z) {
        PositionGroup group;
        if (firstReusableGroup == null) {
            group = new PositionGroup();
        } else {
            group = firstReusableGroup;
            firstReusableGroup = group.next;
        }

        group.x = x;
        group.z = z;
        group.next = groupsByHash.put(positionHash(x, z), group);
        return group;
    }

    private PositionGroup findGroup(double x, double z) {
        long hash = positionHash(x, z);
        for (PositionGroup group = groupsByHash.get(hash); group != null; group = group.next) {
            if (group.x == x && group.z == z) {
                return group;
            }
        }

        return null;
    }

    private void leaveGroup(int slot, PositionGroup group) {
        entityGroups[slot] = null;
        if (group.memberBitmap != null) {
            setGroupMember(group, slot, false);
        }

        group.memberCount--;
        if (group.memberCount == 0) {
            recycleGroup(group);
        }
    }

    private void recycleGroup(PositionGroup group) {
        long hash = positionHash(group.x, group.z);
        PositionGroup firstInBucket = groupsByHash.get(hash);
        if (firstInBucket == group) {
            if (group.next == null) {
                groupsByHash.remove(hash);
            } else {
                groupsByHash.put(hash, group.next);
            }
        } else {
            PositionGroup previousGroup = firstInBucket;
            while (previousGroup.next != group) {
                previousGroup = previousGroup.next;
            }
            previousGroup.next = group.next;
        }

        group.next = firstReusableGroup;
        firstReusableGroup = group;
    }

    private void allocateGroupBitmap(PositionGroup group) {
        if (allocatedBitmapBytes > bitmapBudget - bytesPerBitmap) {
            return;
        }

        group.memberBitmap = ByteBuffer.allocateDirect(bytesPerBitmap).order(ByteOrder.nativeOrder());
        group.memberBitmapAddress = RealtimeNative.address(group.memberBitmap);
        allocatedBitmapBytes += bytesPerBitmap;

        for (int slot = 0; slot < entityGroups.length; slot++) {
            if (entityGroups[slot] == group) {
                setGroupMember(group, slot, true);
            }
        }
    }

    private static long positionHash(double x, double z) {
        long bitsX = x == 0 ? 0 : Double.doubleToLongBits(x);
        long bitsZ = z == 0 ? 0 : Double.doubleToLongBits(z);
        return HashCommon.mix(bitsX ^ Long.rotateLeft(bitsZ, Integer.SIZE));
    }

    private static void setGroupMember(PositionGroup group, int slot, boolean set) {
        int byteOffset = slot / Long.SIZE * Long.BYTES;
        long bitMask = 1L << (slot % Long.SIZE);
        long currentWord = group.memberBitmap.getLong(byteOffset);
        long updatedWord = set ? currentWord | bitMask : currentWord & ~bitMask;

        group.memberBitmap.putLong(byteOffset, updatedWord);
    }
}

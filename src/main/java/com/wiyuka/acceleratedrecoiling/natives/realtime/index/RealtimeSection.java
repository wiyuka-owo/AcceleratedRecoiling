package com.wiyuka.acceleratedrecoiling.natives.realtime.index;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import it.unimi.dsi.fastutil.ints.IntArrayList;

public final class RealtimeSection {
    private enum EntityField {
        BOUNDS_MIN_X,
        BOUNDS_MIN_Y,
        BOUNDS_MIN_Z,
        BOUNDS_MAX_X,
        BOUNDS_MAX_Y,
        BOUNDS_MAX_Z,
        POS_X,
        POS_Z,
        FLAGS;

        int byteOffset(int stride, int slot) {
            return (ordinal() * stride + slot) * Double.BYTES;
        }

        int quantizedByteOffset(int stride, int slot) {
            int quantizedStart = ENTITY_FIELD_COUNT * stride * Double.BYTES;
            return quantizedStart + (ordinal() * stride + slot) * Integer.BYTES;
        }

        boolean isMinimumBound() {
            return ordinal() < BOUNDS_MAX_X.ordinal();
        }
    }

    private static final int ENTITY_FIELD_COUNT = EntityField.values().length;
    private static final EntityField[] BOUNDS_FIELDS = {
            EntityField.BOUNDS_MIN_X, EntityField.BOUNDS_MIN_Y, EntityField.BOUNDS_MIN_Z,
            EntityField.BOUNDS_MAX_X, EntityField.BOUNDS_MAX_Y, EntityField.BOUNDS_MAX_Z
    };

    public static final class View {
        public final Entity[] entities;
        public final BlockPos sectionPosition;
        public final long address;
        public final int stride;
        private final ByteBuffer boxes;
        private final boolean quantized;
        private final SpatialIndex spatial;
        private int count;
        private int liveCount;
        private int pins;

        private View(Entity[] entities, ByteBuffer boxes, int count, boolean quantized) {
            this.entities = entities;
            this.sectionPosition = SectionPos.of(entities[0].blockPosition()).origin();
            this.boxes = boxes;
            this.address = RealtimeNative.address(boxes);
            this.stride = entities.length;
            this.count = count;
            this.liveCount = count;
            this.quantized = quantized;
            this.spatial = SpatialIndex.enabled(count)
                    ? new SpatialIndex(stride, sectionPosition)
                    : null;
        }

        public int count() {
            return count;
        }

        public long spatialAddress() {
            return spatial == null ? 0 : spatial.address();
        }

        public long coincidentAddress(double x, double z) {
            return spatial == null ? 0 : spatial.coincidentAddress(x, z);
        }

        public View retain() {
            pins++;

            return this;
        }

        public void release() {
            if (--pins < 0) {
                throw new IllegalStateException("Unbalanced section view release");
            }
        }
    }

    private View view;
    private boolean dirty = true;
    private boolean softKnown;
    private int nonSoft;
    private final boolean ordered = BatchedRules.orderedSections();
    private final IntArrayList stateChanges = new IntArrayList();
    private boolean[] stateQueued = new boolean[0];
    private long policyEpoch = Long.MIN_VALUE;

    public View currentView() {
        return view;
    }

    public void stateDirty(int slot) {
        if (slot >= 0 && slot < stateQueued.length && !stateQueued[slot]) {
            stateQueued[slot] = true;
            stateChanges.add(slot);
        }
    }

    public void prepareBatch(long epoch) {
        if (policyEpoch != epoch) {
            stateChanges.clear();

            for (int slot = 0; slot < view.count; slot++) {
                if (view.entities[slot] == null) {
                    continue;
                }

                stateQueued[slot] = true;
                stateChanges.add(slot);
            }

            policyEpoch = epoch;
        }

        for (int changeIndex = 0; changeIndex < stateChanges.size(); changeIndex++) {
            int slot = stateChanges.getInt(changeIndex);
            Entity entity = view.entities[slot];
            stateQueued[slot] = false;

            if (entity == null) {
                continue;
            }

            double x = entity.getX();
            double z = entity.getZ();
            int flag = BatchedRules.classify(entity);
            writeState(slot, x, z, flag);
        }

        stateChanges.clear();
    }

    private void writeState(int slot, double x, double z, double flag) {
        view.boxes.putDouble(EntityField.POS_X.byteOffset(view.stride, slot), x);
        view.boxes.putDouble(EntityField.POS_Z.byteOffset(view.stride, slot), z);
        view.boxes.putDouble(EntityField.FLAGS.byteOffset(view.stride, slot), flag);

        if (view.spatial != null) {
            view.spatial.updateState(slot, x, z, flag);
        }
    }

    public boolean softOnly(EntitySection<?> section) {
        if (!softKnown) {
            nonSoft = 0;
            var iterator = section.getEntities().iterator();

            while (iterator.hasNext()) {
                if (!BatchedRules.soft(iterator.next().getClass())) {
                    nonSoft++;
                }
            }

            softKnown = true;
        }

        return nonSoft == 0;
    }

    public void added(Object object) {
        if (softKnown && !BatchedRules.soft(object.getClass())) {
            nonSoft++;
        }

        if (shouldRebuildView(object)) {
            dirty = true;
            return;
        }

        Entity entity = (Entity) object;
        int slot = view.count++;
        view.liveCount++;
        view.entities[slot] = entity;

        ((IndexedEntity) entity).ar$bindSection(this, slot);
        update(slot, entity.getBoundingBox());
    }

    private boolean shouldRebuildView(Object object) {
        return !(object instanceof Entity)
                || !ordered
                || dirty
                || view.pins != 0
                || view.count == view.stride
                || RealtimeNative.quantizeSection(view.count + 1) != view.quantized
                || SpatialIndex.enabled(view.count + 1) == (view.spatial == null);
    }

    public void removed(Object entity) {
        if (softKnown && !BatchedRules.soft(entity.getClass())) {
            nonSoft--;
        }
        if (entity instanceof IndexedEntity indexed) {
            int slot = indexed.ar$sectionSlot();

            if (!ordered
                    || dirty
                    || view.pins != 0
                    || indexed.ar$section() != this
                    || slot < 0
                    || slot >= view.count
                    || view.entities[slot] != entity) {
                dirty = true;
            } else {
                view.entities[slot] = null;
                view.liveCount--;
                clearBox(slot);

                if (view.count > 32 && view.liveCount < view.count - view.count / 4) {
                    dirty = true;
                }
            }
            indexed.ar$unbindSection(this);
        } else {
            dirty = true;
        }
    }

    public View view(EntitySection<?> section) {
        if (!dirty) {
            return view;
        }
        View previous = view;
        boolean[] previousQueued = stateQueued;
        var entries = section.getEntities().toArray();

        int requiredCapacity = Math.addExact(entries.length, Math.max(1, entries.length / 4));
        int stride = 16;

        while (stride < requiredCapacity) {
            stride = Math.multiplyExact(stride, 2);
        }

        // avoid l1 cache-set aliasing between soa planes
        if (stride >= 512) {
            stride = Math.addExact(stride, 16);
        }

        var entities = new Entity[stride];
        for (int slot = 0; slot < entries.length; slot++) {
            if (!(entries[slot] instanceof Entity entity)) {
                return null;
            }
            entities[slot] = entity;
        }

        boolean quantized = RealtimeNative.quantizeSection(entries.length);
        int bytesPerEntity = ENTITY_FIELD_COUNT * Double.BYTES;
        if (quantized) {
            bytesPerEntity += BOUNDS_FIELDS.length * Integer.BYTES;
        }

        ByteBuffer boxes = ByteBuffer.allocateDirect(Math.multiplyExact(stride, bytesPerEntity))
                .order(ByteOrder.nativeOrder());

        view = new View(entities, boxes, entries.length, quantized);

        dirty = false;
        stateQueued = new boolean[entities.length];
        stateChanges.clear();

        for (int slot = 0; slot < entries.length; slot++) {
            IndexedEntity indexed = (IndexedEntity) entities[slot];
            int oldSlot = indexed.ar$sectionSlot();

            boolean cached = previous != null
                    && indexed.ar$section() == this
                    && oldSlot >= 0
                    && oldSlot < previous.count
                    && previous.entities[oldSlot] == entities[slot];

            indexed.ar$bindSection(this, slot);
            writeBox(slot, entities[slot].getBoundingBox());

            if (cached && !previousQueued[oldSlot]) {
                double x = previous.boxes.getDouble(EntityField.POS_X.byteOffset(previous.stride, oldSlot));
                double z = previous.boxes.getDouble(EntityField.POS_Z.byteOffset(previous.stride, oldSlot));
                double flag = previous.boxes.getDouble(EntityField.FLAGS.byteOffset(previous.stride, oldSlot));
                writeState(slot, x, z, flag);
            } else {
                stateDirty(slot);
            }
        }

        return view;
    }

    public void update(int slot, AABB box) {
        writeBox(slot, box);
        stateDirty(slot);
    }

    private void clearBox(int slot) {
        if (view.spatial != null) {
            view.spatial.remove(slot);
        }

        for (EntityField field : BOUNDS_FIELDS) {
            double emptyBound = field.isMinimumBound() ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            view.boxes.putDouble(field.byteOffset(view.stride, slot), emptyBound);
        }

        if (view.quantized) {
            for (EntityField field : BOUNDS_FIELDS) {
                int emptyBound = field.isMinimumBound() ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                view.boxes.putInt(field.quantizedByteOffset(view.stride, slot), emptyBound);
            }
        }
    }

    private void writeBox(int slot, AABB box) {
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;
        ByteBuffer buffer = view.boxes;
        int stride = view.stride;

        buffer.putDouble(EntityField.BOUNDS_MIN_X.byteOffset(stride, slot), minX);
        buffer.putDouble(EntityField.BOUNDS_MIN_Y.byteOffset(stride, slot), minY);
        buffer.putDouble(EntityField.BOUNDS_MIN_Z.byteOffset(stride, slot), minZ);
        buffer.putDouble(EntityField.BOUNDS_MAX_X.byteOffset(stride, slot), maxX);
        buffer.putDouble(EntityField.BOUNDS_MAX_Y.byteOffset(stride, slot), maxY);
        buffer.putDouble(EntityField.BOUNDS_MAX_Z.byteOffset(stride, slot), maxZ);

        if (view.quantized || view.spatial != null) {
            boolean finite = Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ);
            int quantizedMinX = finite ? quantize(minX) : Integer.MIN_VALUE;
            int quantizedMinY = finite ? quantize(minY) : Integer.MIN_VALUE;
            int quantizedMinZ = finite ? quantize(minZ) : Integer.MIN_VALUE;
            int quantizedMaxX = finite ? quantize(maxX) : Integer.MAX_VALUE;
            int quantizedMaxY = finite ? quantize(maxY) : Integer.MAX_VALUE;
            int quantizedMaxZ = finite ? quantize(maxZ) : Integer.MAX_VALUE;

            if (view.quantized) {
                buffer.putInt(EntityField.BOUNDS_MIN_X.quantizedByteOffset(stride, slot), quantizedMinX);
                buffer.putInt(EntityField.BOUNDS_MIN_Y.quantizedByteOffset(stride, slot), quantizedMinY);
                buffer.putInt(EntityField.BOUNDS_MIN_Z.quantizedByteOffset(stride, slot), quantizedMinZ);
                buffer.putInt(EntityField.BOUNDS_MAX_X.quantizedByteOffset(stride, slot), quantizedMaxX);
                buffer.putInt(EntityField.BOUNDS_MAX_Y.quantizedByteOffset(stride, slot), quantizedMaxY);
                buffer.putInt(EntityField.BOUNDS_MAX_Z.quantizedByteOffset(stride, slot), quantizedMaxZ);
            }

            if (view.spatial != null) {
                view.spatial.updateBounds(slot, quantizedMinX, quantizedMinY, quantizedMinZ,
                        quantizedMaxX, quantizedMaxY, quantizedMaxZ, finite);
            }
        }
    }

    private static int quantize(double coordinate) {
        return (int) Math.floor(coordinate * 64.0);
    }
}

package com.wiyuka.acceleratedrecoiling.natives.realtime.index;

import com.wiyuka.acceleratedrecoiling.natives.realtime.RealtimeNative;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

public final class RealtimeSection {
    private View view;
    private boolean dirty = true;
    private boolean softKnown;
    private int nonSoft;

    public View currentView() {
        return view;
    }

    public void stateDirty(int slot) {
        if (view != null) {
            view.stateDirty(slot);
        }
    }

    public void prepareBatch(long epoch) {
        view.prepareBatch(epoch);
    }

    public void update(int slot, AABB box) {
        view.update(slot, box);
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

        view.add(this, (Entity) object);
    }

    private boolean shouldRebuildView(Object object) {
        return !(object instanceof Entity) || dirty || !view.canAdd();
    }

    public void removed(Object entity) {
        if (softKnown && !BatchedRules.soft(entity.getClass())) {
            nonSoft--;
        }

        if (!(entity instanceof IndexedEntity indexed)) {
            dirty = true;
            return;
        }

        int slot = indexed.ar$sectionSlot();
        if (dirty || indexed.ar$section() != this
                || !view.remove(entity, slot)) {
            dirty = true;
        } else {
            dirty = view.needsCompaction();
        }
        indexed.ar$unbindSection(this);
    }

    public View view(EntitySection<?> section) {
        if (!dirty) {
            return view;
        }

        View previous = view;
        View replacement = View.create(section);
        if (replacement == null) {
            return null;
        }

        view = replacement;
        dirty = false;
        view.initialize(this, previous);
        return view;
    }

    public static final class View {
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

        public final Entity[] entities;
        public final BlockPos sectionPosition;
        public final long address;
        public final int stride;
        private final ByteBuffer boxes;
        private final boolean quantized;
        private final SpatialIndex spatial;
        private final BitSet stateChanges;
        private long policyEpoch = Long.MIN_VALUE;
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
            this.stateChanges = new BitSet(stride);
        }

        private static View create(EntitySection<?> section) {
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

            return new View(entities, boxes, entries.length, quantized);
        }

        private void initialize(RealtimeSection owner, View previous) {
            if (previous != null) {
                policyEpoch = previous.policyEpoch;
            }

            int initialCount = count;
            for (int slot = 0; slot < initialCount; slot++) {
                IndexedEntity indexed = (IndexedEntity) entities[slot];
                int oldSlot = indexed.ar$sectionSlot();

                boolean cached = previous != null
                        && indexed.ar$section() == owner
                        && oldSlot >= 0
                        && oldSlot < previous.count
                        && previous.entities[oldSlot] == entities[slot];

                indexed.ar$bindSection(owner, slot);
                writeBox(slot, entities[slot].getBoundingBox());

                if (cached && !previous.stateChanges.get(oldSlot)) {
                    double x = previous.boxes.getDouble(EntityField.POS_X.byteOffset(previous.stride, oldSlot));
                    double z = previous.boxes.getDouble(EntityField.POS_Z.byteOffset(previous.stride, oldSlot));
                    double flag = previous.boxes.getDouble(EntityField.FLAGS.byteOffset(previous.stride, oldSlot));
                    writeState(slot, x, z, flag);
                } else {
                    stateDirty(slot);
                }
            }
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

        private boolean canAdd() {
            return pins == 0 && count < stride
                    && RealtimeNative.quantizeSection(count + 1) == quantized
                    && SpatialIndex.enabled(count + 1) == (spatial != null);
        }

        private void add(RealtimeSection owner, Entity entity) {
            int slot = count++;
            liveCount++;
            entities[slot] = entity;

            ((IndexedEntity) entity).ar$bindSection(owner, slot);
            update(slot, entity.getBoundingBox());
        }

        private boolean remove(Object entity, int slot) {
            if (pins != 0 || slot < 0 || slot >= count || entities[slot] != entity) {
                return false;
            }

            entities[slot] = null;
            liveCount--;
            clearBox(slot);
            return true;
        }

        private boolean needsCompaction() {
            return count > 32 && liveCount < count - count / 4;
        }

        private void stateDirty(int slot) {
            if (slot >= 0 && slot < stride) {
                stateChanges.set(slot);
            }
        }

        private void prepareBatch(long epoch) {
            if (policyEpoch != epoch) {
                stateChanges.clear();
                stateChanges.set(0, count);
                policyEpoch = epoch;
            }

            for (int slot = stateChanges.nextSetBit(0); slot >= 0; slot = stateChanges.nextSetBit(slot + 1)) {
                Entity entity = entities[slot];

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

        private void update(int slot, AABB box) {
            writeBox(slot, box);
            stateDirty(slot);
        }

        private void writeState(int slot, double x, double z, double flag) {
            boxes.putDouble(EntityField.POS_X.byteOffset(stride, slot), x);
            boxes.putDouble(EntityField.POS_Z.byteOffset(stride, slot), z);
            boxes.putDouble(EntityField.FLAGS.byteOffset(stride, slot), flag);

            if (spatial != null) {
                spatial.updateState(slot, x, z, flag);
            }
        }

        private void clearBox(int slot) {
            if (spatial != null) {
                spatial.remove(slot);
            }

            for (EntityField field : BOUNDS_FIELDS) {
                double emptyBound = field.isMinimumBound() ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
                boxes.putDouble(field.byteOffset(stride, slot), emptyBound);
            }

            if (quantized) {
                for (EntityField field : BOUNDS_FIELDS) {
                    int emptyBound = field.isMinimumBound() ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                    boxes.putInt(field.quantizedByteOffset(stride, slot), emptyBound);
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
            ByteBuffer buffer = boxes;

            buffer.putDouble(EntityField.BOUNDS_MIN_X.byteOffset(stride, slot), minX);
            buffer.putDouble(EntityField.BOUNDS_MIN_Y.byteOffset(stride, slot), minY);
            buffer.putDouble(EntityField.BOUNDS_MIN_Z.byteOffset(stride, slot), minZ);
            buffer.putDouble(EntityField.BOUNDS_MAX_X.byteOffset(stride, slot), maxX);
            buffer.putDouble(EntityField.BOUNDS_MAX_Y.byteOffset(stride, slot), maxY);
            buffer.putDouble(EntityField.BOUNDS_MAX_Z.byteOffset(stride, slot), maxZ);

            if (quantized || spatial != null) {
                boolean finite = Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                        && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ);
                int quantizedMinX = finite ? quantize(minX) : Integer.MIN_VALUE;
                int quantizedMinY = finite ? quantize(minY) : Integer.MIN_VALUE;
                int quantizedMinZ = finite ? quantize(minZ) : Integer.MIN_VALUE;
                int quantizedMaxX = finite ? quantize(maxX) : Integer.MAX_VALUE;
                int quantizedMaxY = finite ? quantize(maxY) : Integer.MAX_VALUE;
                int quantizedMaxZ = finite ? quantize(maxZ) : Integer.MAX_VALUE;

                if (quantized) {
                    buffer.putInt(EntityField.BOUNDS_MIN_X.quantizedByteOffset(stride, slot), quantizedMinX);
                    buffer.putInt(EntityField.BOUNDS_MIN_Y.quantizedByteOffset(stride, slot), quantizedMinY);
                    buffer.putInt(EntityField.BOUNDS_MIN_Z.quantizedByteOffset(stride, slot), quantizedMinZ);
                    buffer.putInt(EntityField.BOUNDS_MAX_X.quantizedByteOffset(stride, slot), quantizedMaxX);
                    buffer.putInt(EntityField.BOUNDS_MAX_Y.quantizedByteOffset(stride, slot), quantizedMaxY);
                    buffer.putInt(EntityField.BOUNDS_MAX_Z.quantizedByteOffset(stride, slot), quantizedMaxZ);
                }

                if (spatial != null) {
                    spatial.updateBounds(slot, quantizedMinX, quantizedMinY, quantizedMinZ,
                            quantizedMaxX, quantizedMaxY, quantizedMaxZ, finite);
                }
            }
        }

        private static int quantize(double coordinate) {
            return (int) Math.floor(coordinate * 64.0);
        }
    }
}

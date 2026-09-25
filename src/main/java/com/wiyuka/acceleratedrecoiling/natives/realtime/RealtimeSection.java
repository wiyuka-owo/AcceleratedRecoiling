package com.wiyuka.acceleratedrecoiling.natives.realtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;
import it.unimi.dsi.fastutil.ints.IntArrayList;

public final class RealtimeSection {
    public static final class View {
        private final Entity[] entities;
        private final ByteBuffer boxes;
        private final long address;
        private final int stride;
        private final boolean quantized;
        private int count;
        private int liveCount;
        private int pins;

        private View(Entity[] entities, ByteBuffer boxes, int count, boolean quantized) {
            this.entities = entities;
            this.boxes = boxes;
            this.address = RealtimeNative.address(boxes);
            this.stride = entities.length;
            this.count = count;
            this.liveCount = count;
            this.quantized = quantized;
        }

        public Entity[] entities() {
            return entities;
        }

        public long address() {
            return address;
        }

        public int stride() {
            return stride;
        }

        public int count() {
            return count;
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
            for (int i = 0; i < view.count; i++) {
                if (view.entities[i] == null) {
                    continue;
                }
                stateQueued[i] = true;
                stateChanges.add(i);
            }
            policyEpoch = epoch;
        }
        for (int i = 0; i < stateChanges.size(); i++) {
            int slot = stateChanges.getInt(i);
            Entity entity = view.entities[slot];
            stateQueued[slot] = false;
            if (entity == null) {
                continue;
            }
            if (BatchDiagnostics.ENABLED) {
                BatchDiagnostics.preparedEntities++;
            }
            int plane = view.stride * 8;
            view.boxes.putDouble(6 * plane + slot * 8, entity.getX());
            view.boxes.putDouble(7 * plane + slot * 8, entity.getZ());
            view.boxes.putDouble(8 * plane + slot * 8, BatchedRules.classify(entity));
        }
        stateChanges.clear();
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
        if (!(object instanceof Entity entity) || !ordered || dirty || view == null || view.pins != 0
                || view.count == view.stride || RealtimeNative.quantizeSection(view.count + 1) != view.quantized) {
            dirty = true;
            return;
        }
        int slot = view.count++;
        view.liveCount++;
        view.entities[slot] = entity;
        ((IndexedEntity) entity).ar$bindSection(this, slot);
        update(slot, entity.getBoundingBox());
    }

    public void removed(Object entity) {
        if (softKnown && !BatchedRules.soft(entity.getClass())) {
            nonSoft--;
        }
        if (entity instanceof IndexedEntity indexed) {
            int slot = indexed.ar$sectionSlot();
            if (!ordered || dirty || view == null || view.pins != 0 || indexed.ar$section() != this || slot < 0
                    || slot >= view.count || view.entities[slot] != entity) {
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
        if (BatchDiagnostics.ENABLED) {
            BatchDiagnostics.rebuilds++;
        }
        View previous = view;
        boolean[] previousQueued = stateQueued;
        var entries = section.getEntities().toArray();
        int requiredCapacity = Math.addExact(entries.length, Math.max(1, entries.length / 4));
        int stride = 16;
        while (stride < requiredCapacity) stride = Math.multiplyExact(stride, 2);
        // avoid l1 cache-set aliasing between soa planes
        if (stride >= 512) stride = Math.addExact(stride, 16);
        var entities = new Entity[stride];
        for (int i = 0; i < entries.length; i++) {
            if (!(entries[i] instanceof Entity e)) {
                return null;
            }
            entities[i] = e;
        }
        boolean quantized = RealtimeNative.quantizeSection(entries.length);
        int bytesPerEntity = 9 * Double.BYTES;
        if (quantized) {
            bytesPerEntity += 6 * Integer.BYTES;
        }
        ByteBuffer boxes = ByteBuffer.allocateDirect(Math.multiplyExact(stride, bytesPerEntity))
                .order(ByteOrder.nativeOrder());
        if (BatchDiagnostics.ENABLED) {
            BatchDiagnostics.allocatedBytes += boxes.capacity();
        }
        view = new View(entities, boxes, entries.length, quantized);
        if (view.address == 0) {
            throw new IllegalStateException("Direct box buffer has no address");
        }
        dirty = false;
        stateQueued = new boolean[entities.length];
        stateChanges.clear();
        for (int i = 0; i < entries.length; i++) {
            IndexedEntity indexed = (IndexedEntity) entities[i];
            int oldSlot = indexed.ar$sectionSlot();

            boolean cached =
                    previous != null
                 && indexed.ar$section() == this
                 && oldSlot >= 0
                 && oldSlot < previous.count
                 && previous.entities[oldSlot] == entities[i];

            indexed.ar$bindSection(this, i);
            writeBox(i, entities[i].getBoundingBox());
            if (cached && !previousQueued[oldSlot]) {
                for (int p = 6; p < 9; p++) {
                    boxes.putDouble(
                            (p * stride + i) * 8,
                            previous.boxes.getDouble((p * previous.stride + oldSlot) * 8)
                    );
                }
            } else {
                stateDirty(i);
            }
        }
        return view;
    }

    public void update(int slot, AABB box) {
        if (view == null) {
            return;
        }
        writeBox(slot, box);
        stateDirty(slot);
    }

    private void clearBox(int slot) {
        int plane = view.stride * 8;
        for (int p = 0; p < 6; p++) {
            view.boxes.putDouble(
                    p * plane + slot * 8,
                    p < 3 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }

        if (view.quantized) {
            int offset = 9 * plane + slot * Integer.BYTES;
            int step = view.stride * Integer.BYTES;
            for (int p = 0; p < 6; p++) {
                view.boxes.putInt(
                        offset + p * step,
                        p < 3 ? Integer.MAX_VALUE : Integer.MIN_VALUE);
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
        int offset = slot * Double.BYTES;
        int planeBytes = view.stride * Double.BYTES;
        buffer.putDouble(offset, minX);
        buffer.putDouble(planeBytes + offset, minY);
        buffer.putDouble(2 * planeBytes + offset, minZ);
        buffer.putDouble(3 * planeBytes + offset, maxX);
        buffer.putDouble(4 * planeBytes + offset, maxY);
        buffer.putDouble(5 * planeBytes + offset, maxZ);
        if (view.quantized) {
            int quantizedOffset = 9 * planeBytes + slot * Integer.BYTES;
            int quantizedPlaneBytes = view.stride * Integer.BYTES;
            if (Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ) && Double.isFinite(maxX)
                    && Double.isFinite(maxY) && Double.isFinite(maxZ)) {
                buffer.putInt(quantizedOffset, quantize(minX));
                buffer.putInt(quantizedOffset + quantizedPlaneBytes, quantize(minY));
                buffer.putInt(quantizedOffset + 2 * quantizedPlaneBytes, quantize(minZ));
                buffer.putInt(quantizedOffset + 3 * quantizedPlaneBytes, quantize(maxX));
                buffer.putInt(quantizedOffset + 4 * quantizedPlaneBytes, quantize(maxY));
                buffer.putInt(quantizedOffset + 5 * quantizedPlaneBytes, quantize(maxZ));
            } else {
                for (int j = 0; j < 6; j++) {
                    buffer.putInt(quantizedOffset + j * quantizedPlaneBytes,
                            j < 3 ? Integer.MIN_VALUE : Integer.MAX_VALUE);
                }
            }
        }
    }

    private static int quantize(double coordinate) {
        return (int) Math.floor(coordinate * 64.0);
    }
}

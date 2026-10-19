package com.wiyuka.acceleratedrecoiling.natives.realtime;

import net.neoforged.neoforge.common.config.NeoForgeServerConfig;
import com.wiyuka.acceleratedrecoiling.mixin.BatchedLevelAccess;
import com.wiyuka.acceleratedrecoiling.mixin.RealtimeGetterAccess;
import com.wiyuka.acceleratedrecoiling.natives.realtime.compat.BatchedRules;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedEntity;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.IndexedSection;
import com.wiyuka.acceleratedrecoiling.natives.realtime.index.RealtimeSection;
import com.wiyuka.acceleratedrecoiling.natives.realtime.movement.BlockCollisionCache;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer.Continuation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class BatchedCollisions {
    private static final int SECTION_ADDRESS_OFFSET = 0;
    private static final int SECTION_COUNT_OFFSET = SECTION_ADDRESS_OFFSET + Long.BYTES;
    private static final int SECTION_STRIDE_OFFSET = SECTION_COUNT_OFFSET + Integer.BYTES;
    private static final int SECTION_SPATIAL_OFFSET = SECTION_STRIDE_OFFSET + Integer.BYTES;
    private static final int SECTION_COINCIDENT_OFFSET = SECTION_SPATIAL_OFFSET + Long.BYTES;
    private static final int SECTION_DESCRIPTOR_BYTES = SECTION_COINCIDENT_OFFSET + Long.BYTES;

    private static final boolean NATIVE_PUSH = Boolean.parseBoolean(System.getProperty("ar.nativePush", "true"));
    private static final int OUTPUT_FIELD_COUNT = NATIVE_PUSH
            ? OutputField.values().length : OutputField.PUSH_TARGETS.ordinal() + 1;
    private static final int OUTPUT_BYTES_PER_ENTRY = OUTPUT_FIELD_COUNT * Long.BYTES;

    private enum OutputField {
        COLLISIONS,
        PUSH_TARGETS,
        IMPULSE_X,
        IMPULSE_Z;

        int byteOffset(int entries) {
            return ordinal() * entries * Long.BYTES;
        }
    }

    private BatchedCollisions() {
    }

    private static final ThreadLocal<ArrayDeque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);

    private static final class Frame {
        final ArrayList<RealtimeSection.View> sections = new ArrayList<>();
        final ArrayList<Entity> callbackTargets = new ArrayList<>();

        ByteBuffer sectionDescriptors = buffer(256);
        ByteBuffer output = buffer(4096);

        int entryCount;
        int sourceSection;

        void ensureCapacity() {
            if (sectionDescriptors.capacity() < (long) sections.size() * SECTION_DESCRIPTOR_BYTES) {
                sectionDescriptors = buffer(Math.multiplyExact(sections.size(), SECTION_DESCRIPTOR_BYTES * 2));
            }

            if (output.capacity() < (long) entryCount * OUTPUT_BYTES_PER_ENTRY) {
                output = buffer(Math.multiplyExact(entryCount, OUTPUT_BYTES_PER_ENTRY * 2));
            }
        }

        void prepareSectionDescriptors(LivingEntity source) {
            Frame frame = this;

            frame.ensureCapacity();

            var owner = ((IndexedEntity) source).ar$section();
            var sourceView = owner == null ? null : owner.currentView();
            frame.sourceSection = -1;

            for (int sectionIndex = 0; sectionIndex < frame.sections.size(); sectionIndex++) {
                var section = frame.sections.get(sectionIndex);
                if (section == sourceView) {
                    frame.sourceSection = sectionIndex;
                }

                int offset = sectionIndex * SECTION_DESCRIPTOR_BYTES;
                frame.sectionDescriptors.putLong(offset + SECTION_ADDRESS_OFFSET, section.address);
                frame.sectionDescriptors.putInt(offset + SECTION_COUNT_OFFSET, section.count());
                frame.sectionDescriptors.putInt(offset + SECTION_STRIDE_OFFSET, section.stride);
                frame.sectionDescriptors.putLong(offset + SECTION_SPATIAL_OFFSET, section.spatialAddress());
                frame.sectionDescriptors.putLong(offset + SECTION_COINCIDENT_OFFSET,
                        section.coincidentAddress(source.getX(), source.getZ()));
            }
        }
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    public static void clear() {
        FRAMES.remove();
        BlockCollisionCache.clear();
    }

    @SuppressWarnings("unchecked")
    public static boolean tryPush(LivingEntity source) {
        if (!canPush(source)) {
            return false;
        }

        ServerLevel level = (ServerLevel) source.level();
        if (!level.dragonParts().isEmpty()) {
            return false;
        }
        var getter = ((BatchedLevelAccess) level).ar$entities();
        if (getter.getClass() != LevelEntityGetterAdapter.class) return false;
        var access = (RealtimeGetterAccess<Entity>) getter;

        var storage = access.ar$sectionStorage();

        var frames = FRAMES.get();
        Frame frame = frames.pollFirst();
        if (frame == null) {
            frame = new Frame();
        }

        try {
            long epoch = BatchedRules.epoch();
            var bounds = source.getBoundingBox();

            collectSections(frame, storage, bounds, epoch);

            frame.prepareSectionDescriptors(source);
            return queryAndPush(source, level, bounds, frame);
        } finally {
            Reference.reachabilityFence(frame.sections);
            for (var section : frame.sections) section.release();
            frame.sections.clear();
            frame.callbackTargets.clear();
            frames.addFirst(frame);
        }
    }

    private static boolean canPush(LivingEntity source) {
        if (source.level().getClass() != ServerLevel.class || NeoForgeServerConfig.INSTANCE.fullBoundingBoxLadders.get()) {
            return false;
        }
        var classification = BatchedRules.classify(source, true);
        return classification == CollisionResult.ACCEPTED || classification == CollisionResult.CALLBACKS;
    }

    private static void collectSections(Frame frame, EntitySectionStorage<Entity> storage,
            AABB bounds, long epoch) {
        frame.entryCount = 0;

        storage.forEachAccessibleNonEmptySection(bounds, section -> {
            var index = ((IndexedSection) section).ar$realtimeSection();
            var view = index.view(section);
            index.prepareBatch(epoch);
            frame.sections.add(view.retain());
            frame.entryCount = Math.addExact(frame.entryCount, view.count());
            return Continuation.CONTINUE;
        });
    }

    private static boolean queryAndPush(LivingEntity source,
                                        ServerLevel level,
                                        AABB bounds,
                                        Frame frame) {
        if (BatchedRules.classify(source, true) != CollisionResult.ACCEPTED) {
            return queryAndPushCallbacks(source, level, bounds, frame);
        }

        IndexedEntity indexedSource = (IndexedEntity) source;
        int crammingLimit = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        boolean retainCollisions = crammingLimit > 0;
        long counts = RealtimeNative.queryBatch(frame.sectionDescriptors,
                frame.sections.size(),
                frame.output,
                frame.sourceSection,
                indexedSource.ar$sectionSlot(),
                source.getX(),
                source.getZ(),
                bounds.minX,
                bounds.minY,
                bounds.minZ,
                bounds.maxX,
                bounds.maxY,
                bounds.maxZ,
                retainCollisions,
                NATIVE_PUSH);
        if (counts == -1) {
            return queryAndPushCallbacks(source, level, bounds, frame);
        }

        if (counts < 0) {
            throw new IllegalStateException("Invalid native batch result: " + counts);
        }

        // counts -->
        // ----------- collisionCount -------------   -------------- pushCount ---------------
        // 0000 0000 0000 0000  0000 0000 0000 0000   0000 0000 0000 0000  0000 0000 0000 0000
        int collisionCount = (int) (counts >>> 32);
        int pushCount = (int) counts;

        if (pushCount < 0 || pushCount > collisionCount || collisionCount > frame.entryCount) {
            throw new IllegalStateException("Invalid native batch counts");
        }

        Profiler.get().incrementCounter("getEntities");
        dispatchPushes(source, level, frame, collisionCount, pushCount, crammingLimit);

        return true;
    }

    private static boolean queryAndPushCallbacks(LivingEntity source, ServerLevel level, AABB bounds, Frame frame) {
        long result = RealtimeNative.queryCandidates(frame.sectionDescriptors, frame.sections.size(), frame.output,
                frame.sourceSection, ((IndexedEntity) source).ar$sectionSlot(),
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        if (result == -1) {
            return false;
        }

        int candidateCount = (int) (result >>> Integer.SIZE);
        if (result < 0 || candidateCount > frame.entryCount || (int) result != 0) {
            throw new IllegalStateException("Invalid native candidate result: " + result);
        }

        var pushable = EntitySelector.pushableBy(source);
        int offset = OutputField.COLLISIONS.byteOffset(frame.entryCount);
        Profiler.get().incrementCounter("getEntities");
        for (int index = 0; index < candidateCount; index++) {
            Entity candidate = collisionTarget(frame, offset, index);
            if (pushable.test(candidate)) {
                frame.callbackTargets.add(candidate);
            }
        }

        dispatchCallbacks(source, level, frame.callbackTargets);
        return true;
    }

    private static void dispatchCallbacks(LivingEntity source, ServerLevel level, ArrayList<Entity> targets) {
        if (targets.isEmpty()) {
            return;
        }

        int crammingLimit = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        if (crammingLimit > 0 && targets.size() > crammingLimit - 1
                && source.getRandom().nextInt(4) == 0) {
            int nonPassengers = 0;
            for (Entity candidate : targets) {
                if (!candidate.isPassenger()) {
                    nonPassengers++;
                }
            }
            if (nonPassengers > crammingLimit - 1) {
                source.hurtServer(level, source.damageSources().cramming(), 6.0F);
            }
        }

        for (Entity candidate : targets) {
            source.doPush(candidate);
        }
    }

    private static void dispatchPushes(LivingEntity source,
                                      ServerLevel level,
                                      Frame frame,
                                      int collisionCount,
                                      int pushCount,
                                      int crammingLimit) {
        boolean crammingAttempted = false;
        if (crammingLimit > 0 && collisionCount > crammingLimit - 1
                && source.getRandom().nextInt(4) == 0) {
            source.hurtServer(level, source.damageSources().cramming(), 6.0F);
            crammingAttempted = true;
        }

        int count = crammingAttempted ? collisionCount : pushCount;
        OutputField hits = crammingAttempted ? OutputField.COLLISIONS : OutputField.PUSH_TARGETS;
        int offset = hits.byteOffset(frame.entryCount);
        if (NATIVE_PUSH && !crammingAttempted) {
            dispatchImpulses(source, frame, count, offset);
        } else {
            for (int index = 0; index < count; index++) {
                Entity other = collisionTarget(frame, offset, index);
                source.doPush(other);
            }
        }
    }

    private static Entity collisionTarget(Frame frame, int offset, int index) {
        long hit = frame.output.getLong(offset + index * Long.BYTES);
        int sectionIndex = (int) (hit >>> Integer.SIZE);
        int entitySlot = (int) hit;
        return frame.sections.get(sectionIndex).entities[entitySlot];
    }

    private static void dispatchImpulses(LivingEntity source, Frame frame, int count, int offset) {
        if (count == 0) {
            return;
        }

        Vec3 velocity = source.getDeltaMovement();
        double velocityX = velocity.x;
        double velocityY = velocity.y;
        double velocityZ = velocity.z;
        boolean sourcePushabilityChecked = false;
        boolean sourcePushable = false;
        boolean sourceNeedsSync = false;

        int impulseXOffset = OutputField.IMPULSE_X.byteOffset(frame.entryCount);
        int impulseZOffset = OutputField.IMPULSE_Z.byteOffset(frame.entryCount);
        ByteBuffer output = frame.output;
        int targetSectionIndex = -1;
        Entity[] targets = null;

        for (int index = 0; index < count; index++) {
            long hit = output.getLong(offset + index * Long.BYTES);
            int sectionIndex = (int) (hit >>> Integer.SIZE);
            if (sectionIndex != targetSectionIndex) {
                targetSectionIndex = sectionIndex;
                targets = frame.sections.get(sectionIndex).entities;
            }

            int entitySlot = (int) hit;
            var other = (LivingEntity) targets[entitySlot];
            int impulseOffset = index * Double.BYTES;
            double impulseX = output.getDouble(impulseXOffset + impulseOffset);
            double impulseZ = output.getDouble(impulseZOffset + impulseOffset);
            if (!canPushPair(source, other)) {
                continue;
            }

            if (!other.isVehicle() && isPushableInBatch(other, true)) {
                other.push(-impulseX, 0.0, -impulseZ);
            }

            if (!sourcePushabilityChecked) {
                sourcePushable = !source.isVehicle() && isPushableInBatch(source, frame.sourceSection >= 0);
                sourcePushabilityChecked = true;
            }
            if (!sourcePushable || !Double.isFinite(impulseX) || !Double.isFinite(impulseZ)) {
                continue;
            }

            double nextX = velocityX + impulseX;
            double nextY = velocityY + 0.0;
            double nextZ = velocityZ + impulseZ;
            if (Double.isFinite(nextX) && Double.isFinite(nextY) && Double.isFinite(nextZ)) {
                velocityX = nextX;
                velocityY = nextY;
                velocityZ = nextZ;
            }
            sourceNeedsSync = true;
        }

        if (sourceNeedsSync) {
            source.setDeltaMovement(new Vec3(velocityX, velocityY, velocityZ));
            source.needsSync = true;
        }
    }

    private static boolean canPushPair(LivingEntity source, LivingEntity other) {
        return !other.isSleeping()
                && !other.isPassengerOfSameVehicle(source)
                && !source.noPhysics
                && !other.noPhysics;
    }

    private static boolean isPushableInBatch(LivingEntity entity, boolean sectionTicking) {
        if (sectionTicking) {
            return ((PushableMemoryEntity) entity).ar$isPushableInTickingSection();
        }

        return entity.isPushable();
    }
}

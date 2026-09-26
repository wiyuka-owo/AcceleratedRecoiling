package com.wiyuka.acceleratedrecoiling.natives.realtime;

import com.wiyuka.acceleratedrecoiling.mixin.BatchedLevelAccess;
import com.wiyuka.acceleratedrecoiling.mixin.RealtimeGetterAccess;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.config.NeoForgeServerConfig;

public final class BatchedCollisions {
    private static final int SECTION_ADDRESS_OFFSET = 0;
    private static final int SECTION_COUNT_OFFSET = SECTION_ADDRESS_OFFSET + Long.BYTES;
    private static final int SECTION_STRIDE_OFFSET = SECTION_COUNT_OFFSET + Integer.BYTES;
    private static final int SECTION_DESCRIPTOR_BYTES = SECTION_STRIDE_OFFSET + Integer.BYTES;

    private static final int OUTPUT_BYTES_PER_ENTRY = 2 * Long.BYTES;

    private BatchedCollisions() {
    }

    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);

    private static final class State {
        int depth;
        long queries, handled, candidates, dispatched, fallback;
        final ArrayList<Frame> frames = new ArrayList<>();
    }

    private static final class Frame {
        final ArrayList<RealtimeSection.View> sections = new ArrayList<>();

        ByteBuffer sectionDescriptors = buffer(256);
        ByteBuffer output = buffer(4096);

        int entryCount;
        int sourceSection;

        void capacity(int entries) {
            if (sectionDescriptors.capacity() < (long) sections.size() * SECTION_DESCRIPTOR_BYTES) {
                sectionDescriptors = buffer(Math.multiplyExact(sections.size(), SECTION_DESCRIPTOR_BYTES * 2));
            }

            if (output.capacity() < (long) entries * OUTPUT_BYTES_PER_ENTRY) {
                output = buffer(Math.multiplyExact(entries, OUTPUT_BYTES_PER_ENTRY * 2));
            }
        }
    }

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    public static void clear() {
        LOCAL.remove();
    }

    public static long[] stats() {
        State state = LOCAL.get();
        return new long[] { state.queries, state.handled, state.candidates, state.dispatched, state.fallback };
    }

    @SuppressWarnings("unchecked")
    public static boolean noEntityObstacles(Entity source, AABB area) {
        if (source.level().getClass() != ServerLevel.class || !BatchedRules.cleanWorld()
                || !BatchedRules.plain(source.getClass())) {
            return false;
        }
        ServerLevel level = (ServerLevel) source.level();

        if (!level.dragonParts().isEmpty()) return false;

        var getter = ((BatchedLevelAccess) level).ar$entities();

        if (getter.getClass() != LevelEntityGetterAdapter.class) return false;
        var access = (RealtimeGetterAccess<Entity>) getter;

        boolean[] empty = { true };
        access.ar$sectionStorage().forEachAccessibleNonEmptySection(area.inflate(1.0E-7), section -> {
            if (!((IndexedSection) section).ar$realtimeSection().softOnly(section)) {
                empty[0] = false;
                return AbortableIterationConsumer.Continuation.ABORT;
            }
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        if (empty[0] && !(area.getSize() < 1.0E-7)) {
            Profiler.get().incrementCounter("getEntities");
        }
        return empty[0];
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

        State state = LOCAL.get();
        if (state.depth == state.frames.size()) {
            state.frames.add(new Frame());
        }
        Frame frame = state.frames.get(state.depth++);
        long started = BatchDiagnostics.TIMING ? System.nanoTime() : 0;

        try {
            long epoch = BatchedRules.epoch();
            var bounds = source.getBoundingBox();
            if (!collectSections(frame, storage, bounds, epoch)) return false;

            prepareSectionDescriptors(frame, source);
            return queryAndPush(source, level, bounds, frame, state, started);
        } finally {
            Reference.reachabilityFence(frame.sections);
            for (var section : frame.sections) section.release();
            frame.sections.clear();
            state.depth--;
        }
    }

    private static boolean canPush(LivingEntity source) {
        if (BatchDiagnostics.ENABLED) BatchDiagnostics.attempts++;

        if (
                   source.level().getClass() != ServerLevel.class
                || !BatchedRules.cleanWorld()
                || NeoForgeServerConfig.INSTANCE.fullBoundingBoxLadders.get()
                || BatchedRules.classify(source) != BatchedRules.PUSHABLE
        ) {
            if (BatchDiagnostics.ENABLED) BatchDiagnostics.sourceRejected++;
            return false;
        }
        return true;
    }

    private static boolean collectSections(Frame frame,
                                           EntitySectionStorage<Entity> storage,
                                           AABB bounds,
                                           long epoch) {
        boolean[] supported = { true };

        storage.forEachAccessibleNonEmptySection(bounds, section -> {
            var index = ((IndexedSection) section).ar$realtimeSection();
            var view = index.view(section);
            if (view == null) {
                supported[0] = false;
                return AbortableIterationConsumer.Continuation.ABORT;
            }

            index.prepareBatch(epoch);
            frame.sections.add(view.retain());
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });

        return supported[0];
    }

    private static void prepareSectionDescriptors(Frame frame, LivingEntity source) {
        frame.entryCount = 0;
        for (var section : frame.sections) frame.entryCount = Math.addExact(frame.entryCount, section.count());
        frame.capacity(frame.entryCount);

        var owner = ((IndexedEntity) source).ar$section();
        var sourceView = owner == null ? null : owner.currentView();
        frame.sourceSection = -1;

        for (int sectionIndex = 0; sectionIndex < frame.sections.size(); sectionIndex++) {
            var section = frame.sections.get(sectionIndex);
            if (section == sourceView) {
                frame.sourceSection = sectionIndex;
            }

            int offset = sectionIndex * SECTION_DESCRIPTOR_BYTES;
            frame.sectionDescriptors.putLong(offset + SECTION_ADDRESS_OFFSET, section.address());
            frame.sectionDescriptors.putInt(offset + SECTION_COUNT_OFFSET, section.count());
            frame.sectionDescriptors.putInt(offset + SECTION_STRIDE_OFFSET, section.stride());
        }
    }

    private static boolean queryAndPush(LivingEntity source,
                                        ServerLevel level,
                                        AABB bounds,
                                        Frame frame,
                                        State state,
                                        long started) {
        IndexedEntity indexedSource = (IndexedEntity) source;
        state.queries++;
        long prepared = BatchDiagnostics.TIMING ? System.nanoTime() : 0;
        long counts = RealtimeNative.queryBatch(frame.sectionDescriptors, frame.sections.size(), frame.output, frame.sourceSection,
                indexedSource.ar$sectionSlot(), source.getX(), source.getZ(),
                bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
        long queried = BatchDiagnostics.TIMING ? System.nanoTime() : 0;

        if (BatchDiagnostics.TIMING) {
            BatchDiagnostics.prepareNanos += prepared - started;
            BatchDiagnostics.nativeNanos += queried - prepared;
        }
        if (counts == -1) {
            state.fallback++;
            return false;
        }

        dispatchPushes(source, level, frame, state, counts);
        if (BatchDiagnostics.TIMING) {
            BatchDiagnostics.dispatchNanos += System.nanoTime() - queried;
        }

        return true;
    }

    private static void dispatchPushes(LivingEntity source,
                                       ServerLevel level,
                                       Frame frame,
                                       State state,
                                       long counts) {
        if (counts < 0) {
            throw new IllegalStateException("Invalid native batch result: " + counts);
        }

        int total = (int) (counts >>> Integer.SIZE);
        int nonzero = (int) counts;
        if (nonzero < 0 || nonzero > total || total > frame.entryCount) {
            throw new IllegalStateException("Invalid native batch counts");
        }

        Profiler.get().incrementCounter("getEntities");
        state.handled++;
        state.candidates += total;

        boolean damaged = false;
        if (total > 0) {
            int limit = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
            if (limit > 0 && total > limit - 1 && source.getRandom().nextInt(4) == 0) {
                source.hurtServer(level, source.damageSources().cramming(), 6.0F);
                damaged = true;
            }
        }

        int count = damaged ? total : nonzero;
        int offset = damaged ? 0 : frame.entryCount * Long.BYTES;
        for (int index = 0; index < count; index++) {
            long hit = frame.output.getLong(offset + index * Long.BYTES);
            int sectionIndex = (int) (hit >>> Integer.SIZE);
            int entitySlot = (int) hit;
            Entity other = frame.sections.get(sectionIndex).entities()[entitySlot];
            source.doPush(other);
        }

        state.dispatched += count;
    }
}

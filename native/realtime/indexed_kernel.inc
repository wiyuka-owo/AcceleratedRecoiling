template<bool RetainCollisions>
static bool indexedSection(const Section& section, int sectionIndex, int entryCount,
        std::int64_t* output, const Query& query, const std::int32_t* quantizedBounds,
        int& collisionCount, int& pushCount) {
    const auto* entityData = reinterpret_cast<const double*>(section.address);
    SpatialQuery spatial(*section.spatial, quantizedBounds);
    const auto* eligibleEntities = section.spatial->entityBitmap(SpatialIndex::ELIGIBLE);
    const auto* fallbackEntities = section.spatial->entityBitmap(SpatialIndex::FALLBACK);

    for (int wordIndex = 0; wordIndex < (section.count + 63) / 64; ++wordIndex) {
        std::uint64_t candidateMask, confirmedMask;
        spatial.masks(wordIndex, candidateMask, confirmedMask);
        if (sectionIndex == query.sourceSection && query.sourceSlot / 64 == wordIndex && query.sourceSlot >= 0) {
            candidateMask &= ~(std::uint64_t(1) << (query.sourceSlot % 64));
        }

        candidateMask &= eligibleEntities[wordIndex] | fallbackEntities[wordIndex];
        std::uint64_t pendingBoundsChecks = candidateMask & ~confirmedMask;
        std::uint64_t collisionMask = candidateMask & confirmedMask;

        while (pendingBoundsChecks) {
            const int bit = std::countr_zero(pendingBoundsChecks);
            const int slot = wordIndex * 64 + bit;
            pendingBoundsChecks &= pendingBoundsChecks - 1;

            const bool overlaps = entityData[BOUNDS_MIN_X * section.stride + slot] < query.bounds[BOUNDS_MAX_X]
                    && entityData[BOUNDS_MAX_X * section.stride + slot] > query.bounds[BOUNDS_MIN_X]
                    && entityData[BOUNDS_MIN_Y * section.stride + slot] < query.bounds[BOUNDS_MAX_Y]
                    && entityData[BOUNDS_MAX_Y * section.stride + slot] > query.bounds[BOUNDS_MIN_Y]
                    && entityData[BOUNDS_MIN_Z * section.stride + slot] < query.bounds[BOUNDS_MAX_Z]
                    && entityData[BOUNDS_MAX_Z * section.stride + slot] > query.bounds[BOUNDS_MIN_Z];
            if (overlaps) {
                collisionMask |= std::uint64_t(1) << bit;
            }
        }

        if (collisionMask & fallbackEntities[wordIndex]) {
            return false;
        }

        if constexpr (RetainCollisions) {
            std::uint64_t remainingCollisions = collisionMask;
            while (remainingCollisions) {
                const int slot = wordIndex * 64 + std::countr_zero(remainingCollisions);
                output[collisionCount++] = (std::int64_t(sectionIndex) << 32) | std::uint32_t(slot);
                remainingCollisions &= remainingCollisions - 1;
            }
        } else {
            collisionCount += std::popcount(collisionMask);
        }

        std::uint64_t pushMask = collisionMask & ~(section.coincident ? section.coincident[wordIndex] : 0);

        while (pushMask) {
            const int slot = wordIndex * 64 + std::countr_zero(pushMask);
            pushMask &= pushMask - 1;

            const double deltaX = query.x - entityData[POS_X * section.stride + slot];
            const double deltaZ = query.z - entityData[POS_Z * section.stride + slot];
            if (std::fabs(deltaX) < static_cast<double>(0.01f)
                    && std::fabs(deltaZ) < static_cast<double>(0.01f)) {
                continue;
            }

            output[PUSH_TARGETS * entryCount + pushCount] = (std::int64_t(sectionIndex) << 32) | std::uint32_t(slot);
            if (query.computeImpulses) {
                output[IMPULSE_X * entryCount + pushCount] = std::bit_cast<std::int64_t>(deltaX);
                output[IMPULSE_Z * entryCount + pushCount] = std::bit_cast<std::int64_t>(deltaZ);
            }
            ++pushCount;
        }
    }

    return true;
}

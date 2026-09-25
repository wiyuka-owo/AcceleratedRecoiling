template<bool Quantized>
AR_TARGET
static std::int64_t AR_BATCH(const Section* sections, int sectionCount, int entryCount,
        std::int64_t* output, const Query& query) {
    constexpr unsigned doubleLaneMask = (1u << AR_DLANES) - 1;
    constexpr unsigned integerLaneMask = (1u << AR_ILANES) - 1;

    const auto sourceX = AR_SETD(query.x);
    const auto sourceZ = AR_SETD(query.z);
    const auto pushThreshold = AR_SETD(static_cast<double>(0.01f));
    const auto maxFinite = AR_SETD(DBL_MAX);
    const auto zero = AR_SETD(0.0);

    const auto queryMinX = AR_SETD(query.bounds[BOUNDS_MIN_X]);
    const auto queryMinY = AR_SETD(query.bounds[BOUNDS_MIN_Y]);
    const auto queryMinZ = AR_SETD(query.bounds[BOUNDS_MIN_Z]);
    const auto queryMaxX = AR_SETD(query.bounds[BOUNDS_MAX_X]);
    const auto queryMaxY = AR_SETD(query.bounds[BOUNDS_MAX_Y]);
    const auto queryMaxZ = AR_SETD(query.bounds[BOUNDS_MAX_Z]);

    std::int32_t quantizedQueryBounds[6];
    quantizeBox(query.bounds, quantizedQueryBounds);
    const auto quantizedQueryMinX = AR_SETI(quantizedQueryBounds[BOUNDS_MIN_X]);
    const auto quantizedQueryMinY = AR_SETI(quantizedQueryBounds[BOUNDS_MIN_Y]);
    const auto quantizedQueryMinZ = AR_SETI(quantizedQueryBounds[BOUNDS_MIN_Z]);
    const auto quantizedQueryMaxX = AR_SETI(quantizedQueryBounds[BOUNDS_MAX_X]);
    const auto quantizedQueryMaxY = AR_SETI(quantizedQueryBounds[BOUNDS_MAX_Y]);
    const auto quantizedQueryMaxZ = AR_SETI(quantizedQueryBounds[BOUNDS_MAX_Z]);

    int collisionCount = 0;
    int pushCount = 0;

    for (int section = 0; section < sectionCount; ++section) {
        const auto& currentSection = sections[section];
        const int stride = currentSection.stride;
        const auto* entityData = reinterpret_cast<const double*>(currentSection.address);
        const auto* quantizedBoxes = reinterpret_cast<const std::int32_t*>(entityData + QUANTIZED_BOXES * stride);
        const bool useQuantization = Quantized && currentSection.count >= MIN_QUANTIZED_ENTITIES;

        int blockStart = 0;
        for (; blockStart <= currentSection.count - AR_ILANES; blockStart += AR_ILANES) {
            unsigned candidateMask = integerLaneMask;
            unsigned confirmedMask = 0;

            if (useQuantization) {
                const auto* blockBounds = quantizedBoxes + blockStart;
                const auto candidateMinX = AR_LOADI(blockBounds + BOUNDS_MIN_X * stride);
                const auto candidateMinY = AR_LOADI(blockBounds + BOUNDS_MIN_Y * stride);
                const auto candidateMinZ = AR_LOADI(blockBounds + BOUNDS_MIN_Z * stride);
                const auto candidateMaxX = AR_LOADI(blockBounds + BOUNDS_MAX_X * stride);
                const auto candidateMaxY = AR_LOADI(blockBounds + BOUNDS_MAX_Y * stride);
                const auto candidateMaxZ = AR_LOADI(blockBounds + BOUNDS_MAX_Z * stride);

                const auto outsideX = AR_IOR(AR_IGT(candidateMinX, quantizedQueryMaxX),
                        AR_IGT(quantizedQueryMinX, candidateMaxX));
                const auto outsideY = AR_IOR(AR_IGT(candidateMinY, quantizedQueryMaxY),
                        AR_IGT(quantizedQueryMinY, candidateMaxY));
                const auto outsideZ = AR_IOR(AR_IGT(candidateMinZ, quantizedQueryMaxZ),
                        AR_IGT(quantizedQueryMinZ, candidateMaxZ));
                const unsigned outsideMask = AR_IMASK(AR_IOR(outsideX, AR_IOR(outsideY, outsideZ)));
                candidateMask &= ~outsideMask;
                if (candidateMask == 0) {
                    continue;
                }

                const auto boundaryX = AR_IOR(AR_IEQ(candidateMinX, quantizedQueryMaxX),
                        AR_IEQ(quantizedQueryMinX, candidateMaxX));
                const auto boundaryY = AR_IOR(AR_IEQ(candidateMinY, quantizedQueryMaxY),
                        AR_IEQ(quantizedQueryMinY, candidateMaxY));
                const auto boundaryZ = AR_IOR(AR_IEQ(candidateMinZ, quantizedQueryMaxZ),
                        AR_IEQ(quantizedQueryMinZ, candidateMaxZ));
                const unsigned boundaryMask = AR_IMASK(AR_IOR(boundaryX, AR_IOR(boundaryY, boundaryZ)));
                const unsigned sentinelMask = AR_IMASK(AR_IEQ(candidateMinX, AR_SETI(INT32_MIN)));
                confirmedMask = candidateMask & ~(boundaryMask | sentinelMask);
            }

            for (int laneOffset = 0; laneOffset < AR_ILANES; laneOffset += AR_DLANES) {
                const int firstSlot = blockStart + laneOffset;
                unsigned collisionMask = (candidateMask >> laneOffset) & doubleLaneMask;
                if (section == query.sourceSection
                        && query.sourceSlot >= firstSlot && query.sourceSlot < firstSlot + AR_DLANES) {
                    collisionMask &= ~(1u << (query.sourceSlot - firstSlot));
                }
                if (collisionMask == 0) {
                    continue;
                }

                const auto* groupData = entityData + firstSlot;
                const unsigned confirmedCollisions = (confirmedMask >> laneOffset) & collisionMask;
                if (collisionMask != confirmedCollisions) {
                    const unsigned overlapX = AR_LT(AR_LOADD(groupData + BOUNDS_MIN_X * stride), queryMaxX)
                            & AR_LT(queryMinX, AR_LOADD(groupData + BOUNDS_MAX_X * stride));
                    const unsigned overlapY = AR_LT(AR_LOADD(groupData + BOUNDS_MIN_Y * stride), queryMaxY)
                            & AR_LT(queryMinY, AR_LOADD(groupData + BOUNDS_MAX_Y * stride));
                    const unsigned overlapZ = AR_LT(AR_LOADD(groupData + BOUNDS_MIN_Z * stride), queryMaxZ)
                            & AR_LT(queryMinZ, AR_LOADD(groupData + BOUNDS_MAX_Z * stride));
                    const unsigned exactOverlapMask = overlapX & overlapY & overlapZ;
                    collisionMask = confirmedCollisions | (collisionMask & exactOverlapMask);
                }
                if (collisionMask == 0) {
                    continue;
                }

                const auto flags = AR_LOADD(groupData + FLAGS * stride);
                const unsigned fallbackMask = AR_LT(flags, zero);
                if (collisionMask & fallbackMask) {
                    return -1;
                }
                const unsigned rejectedMask = AR_EQ(flags, zero);
                collisionMask &= ~rejectedMask;
                if (collisionMask == 0) {
                    continue;
                }

                const auto candidateX = AR_LOADD(groupData + POS_X * stride);
                const auto candidateZ = AR_LOADD(groupData + POS_Z * stride);
                const unsigned finitePositionMask = AR_LE(AR_ABS(candidateX), maxFinite)
                        & AR_LE(AR_ABS(candidateZ), maxFinite);
                if (collisionMask & ~finitePositionMask) {
                    return -1;
                }

                const unsigned pushXMask = AR_LE(pushThreshold, AR_ABS(AR_SUB(sourceX, candidateX)));
                const unsigned pushZMask = AR_LE(pushThreshold, AR_ABS(AR_SUB(sourceZ, candidateZ)));
                const unsigned pushMask = collisionMask & (pushXMask | pushZMask);

                const std::int64_t firstHit = (std::int64_t(section) << 32) | std::uint32_t(firstSlot);
                AR_STORE(output + collisionCount, firstHit, collisionMask);
                AR_STORE(output + entryCount + pushCount, firstHit, pushMask);
                collisionCount += std::popcount(collisionMask);
                pushCount += std::popcount(pushMask);
            }
        }

        for (int slot = blockStart; slot < currentSection.count; ++slot) {
            if (!scalarCandidate(entityData, stride, section, slot, entryCount,
                    output, query, collisionCount, pushCount)) {
                return -1;
            }
        }
    }

    return (std::int64_t(collisionCount) << 32) | std::uint32_t(pushCount);
}

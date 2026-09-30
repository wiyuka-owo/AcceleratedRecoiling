#pragma once

#include <algorithm>
#include <cstdint>

namespace ar {

struct SpatialIndex {
    static constexpr int AXIS_COUNT = 3;
    static constexpr int BOUNDS_FIELD_COUNT = AXIS_COUNT * 2;

    enum EntityBitmap : int {
        EMPTY,
        NEEDS_EXACT_BOUNDS,
        ELIGIBLE,
        FALLBACK
    };

    std::int64_t origins[AXIS_COUNT];
    std::int32_t wordCount;
    std::int32_t cellCount;
    std::int32_t cellShift;
    std::int32_t reserved;

    const std::uint64_t* bitmapData() const {
        return reinterpret_cast<const std::uint64_t*>(this + 1);
    }

    const std::uint64_t* entityBitmap(EntityBitmap kind) const {
        const int boundaryRowCount = BOUNDS_FIELD_COUNT * cellCount;
        const int rowIndex = boundaryRowCount + kind;

        return bitmapData() + rowIndex * wordCount;
    }

    const std::uint64_t* boundaryPrefix(int boundsField, int lastCell) const {
        if (lastCell < 0) {
            return entityBitmap(EMPTY);
        }

        const int rowIndex = boundsField * cellCount + lastCell;
        return bitmapData() + rowIndex * wordCount;
    }

    int cellIndex(std::int32_t coordinate, int axis) const {
        const auto localCell = (coordinate - origins[axis]) >> cellShift;
        return static_cast<int>(std::clamp<std::int64_t>(localCell, 0, cellCount - 1));
    }
};

static_assert(sizeof(SpatialIndex) == 40);

struct SpatialQuery {
    const std::uint64_t* candidateMin[SpatialIndex::AXIS_COUNT];
    const std::uint64_t* candidateMax[SpatialIndex::AXIS_COUNT];
    const std::uint64_t* confirmedMin[SpatialIndex::AXIS_COUNT];
    const std::uint64_t* confirmedMax[SpatialIndex::AXIS_COUNT];
    const std::uint64_t* needsExactBounds;

    SpatialQuery(const SpatialIndex& index, const std::int32_t* bounds) {
        for (int axis = 0; axis < SpatialIndex::AXIS_COUNT; ++axis) {
            const int minField = axis;
            const int maxField = axis + SpatialIndex::AXIS_COUNT;
            const int minCell = index.cellIndex(bounds[minField], axis);
            const int maxCell = index.cellIndex(bounds[maxField], axis);

            candidateMin[axis] = index.boundaryPrefix(minField, maxCell);
            candidateMax[axis] = index.boundaryPrefix(maxField, minCell - 1);

            confirmedMin[axis] = index.boundaryPrefix(minField, maxCell - 1);
            confirmedMax[axis] = index.boundaryPrefix(maxField, minCell);
        }

        needsExactBounds = index.entityBitmap(SpatialIndex::NEEDS_EXACT_BOUNDS);
    }

    void masks(int word, std::uint64_t& candidates, std::uint64_t& confirmed) const {
        candidates = candidateMin[0][word] & ~candidateMax[0][word]
                & candidateMin[1][word] & ~candidateMax[1][word]
                & candidateMin[2][word] & ~candidateMax[2][word];

        confirmed = confirmedMin[0][word] & ~confirmedMax[0][word]
                & confirmedMin[1][word] & ~confirmedMax[1][word]
                & confirmedMin[2][word] & ~confirmedMax[2][word]
                & ~needsExactBounds[word];
    }
};

}

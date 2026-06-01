AR_SIMD_TARGET
static int push_compute(SimdTag<SimdType::AR_SIMD_TYPE>, size_t batchBegin, size_t batchEnd, int entityCount, EntityData& data, int *outputA, int *outputB, int K) {
    const int *__restrict pMinY = data.sortedMinY.data();
    const int *__restrict pMaxY = data.sortedMaxY.data();
    const int *__restrict pMinZ = data.sortedMinZ.data();
    const int *__restrict pMaxZ = data.sortedMaxZ.data();
    const int *__restrict pMaxX = data.sortedMaxX.data();
    const int *__restrict pMinX = data.sortedMinX.data();
    const int *__restrict pIDs = data.sortedOriginalIDs.data();

    int* __restrict counts = data.collisionCounts.data();
    int* __restrict runIndexPerItem = data.runIndexPerItem.data();
    auto& runStarts = data.runStarts;

    auto begin_idx = batchBegin * 64;
    auto end_idx = std::min<size_t>(batchEnd * 64, static_cast<size_t>(entityCount));
    auto batchCollisionCount = 0;

    for (int i = static_cast<int>(begin_idx); i < end_idx; ++i)
    {
        int idA = pIDs[i];
        int maxXA = pMaxX[i];
        int minYA = pMinY[i];
        int maxYA = pMaxY[i];
        int minZA = pMinZ[i];
        int maxZA = pMaxZ[i];
        int minXA = pMinX[i];

        int32x4_t vMaxXA = vdupq_n_s32(maxXA);
        int32x4_t vMinYA = vdupq_n_s32(minYA);
        int32x4_t vMaxYA = vdupq_n_s32(maxYA);
        int32x4_t vMinZA = vdupq_n_s32(minZA);
        int32x4_t vMaxZA = vdupq_n_s32(maxZA);
        int32x4_t vMinXA = vdupq_n_s32(minXA);

        int writeOffset = i * K;
        int currentCollisions = 0;

        int* __restrict outA = outputA + writeOffset;
        int* __restrict outB = outputB + writeOffset;

        auto processRange = [&](int start, int end) AR_SIMD_TARGET
        {
            int j = start;

            int alignedStart = (j + 3) & ~3;
            int scalarEnd = std::min(alignedStart, end);
            for (; j < scalarEnd; ++j)
            {
                if (pMinX[j] > maxXA) return;
                if (currentCollisions >= K) return;

                if (!(maxXA <= pMinX[j] || minXA >= pMaxX[j] ||
                      maxYA <= pMinY[j] || minYA >= pMaxY[j] ||
                      maxZA <= pMinZ[j] || minZA >= pMaxZ[j]))
                {
                    outA[currentCollisions] = idA;
                    outB[currentCollisions] = pIDs[j];
                    currentCollisions++;
                }
            }

            const uint32x4_t vShiftMask = {1, 2, 4, 8};

            for (; j < end - 3; j += 4)
            {
                int32x4_t vMinXB = vld1q_s32(&pMinX[j]);
                uint32x4_t vIsAllGreater = vcgtq_s32(vMinXB, vMaxXA);
                if (vminvq_u32(vIsAllGreater) == 0xFFFFFFFF) return;

                int32x4_t vMaxXB = vld1q_s32(&pMaxX[j]);
                uint32x4_t maskX = vandq_u32(vmvnq_u32(vIsAllGreater), vcgtq_s32(vMaxXB, vMinXA));
                if (vmaxvq_u32(maskX) == 0) continue;

                int32x4_t vMinYB = vld1q_s32(&pMinY[j]);
                int32x4_t vMaxYB = vld1q_s32(&pMaxY[j]);
                uint32x4_t maskY = vandq_u32(vcgtq_s32(vMaxYA, vMinYB), vcgtq_s32(vMaxYB, vMinYA));
                uint32x4_t maskXY = vandq_u32(maskX, maskY);
                if (vmaxvq_u32(maskXY) == 0) continue;

                int32x4_t vMinZB = vld1q_s32(&pMinZ[j]);
                int32x4_t vMaxZB = vld1q_s32(&pMaxZ[j]);
                uint32x4_t maskZ = vandq_u32(vcgtq_s32(vMaxZA, vMinZB), vcgtq_s32(vMaxZB, vMinZA));
                uint32x4_t maskXYZ = vandq_u32(maskXY, maskZ);
                if (vmaxvq_u32(maskXYZ) == 0) continue;

                uint32x4_t andedMask = vandq_u32(maskXYZ, vShiftMask);
                int laneMask = vaddvq_u32(andedMask);
                if (laneMask != 0)
                {
                    auto mask8 = static_cast<uint8_t>(laneMask);
                    int cnt = __builtin_popcount(mask8);
                    const auto& bits = CompressTable<128>::instance.table[mask8];
                    int take = std::min(cnt, K - currentCollisions);
                    for (int t = 0; t < take; ++t)
                    {
                        int k = bits[t];
                        outA[currentCollisions] = idA;
                        outB[currentCollisions] = pIDs[j + k];
                        currentCollisions++;
                    }
                    if (currentCollisions >= K) return;
                }
            }

            for (; j < end; ++j)
            {
                if (pMinX[j] > maxXA) return;
                if (currentCollisions >= K) return;

                if (!(maxXA <= pMinX[j] || minXA >= pMaxX[j] ||
                      maxYA <= pMinY[j] || minYA >= pMaxY[j] ||
                      maxZA <= pMinZ[j] || minZA >= pMaxZ[j]))
                {
                    outA[currentCollisions] = idA;
                    outB[currentCollisions] = pIDs[j];
                    currentCollisions++;
                }
            }
        };

        int myRun = runIndexPerItem[i];
        int endOfMyGrid = runStarts[myRun + 1];
        if (i + 1 < endOfMyGrid)
            processRange(i + 1, endOfMyGrid);
        if (currentCollisions < K)
        {
            if (myRun + 2 < (int)runStarts.size())
            {
                int startNext = runStarts[myRun + 1];
                int endNext = runStarts[myRun + 2];
                if (startNext < endNext)
                    processRange(startNext, endNext);
            }
        }
        batchCollisionCount += currentCollisions;
        counts[i] = currentCollisions;
    }
    return batchCollisionCount;
}
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

        __m512i vMaxXA = _mm512_set1_epi32(maxXA);
        __m512i vMinYA = _mm512_set1_epi32(minYA);
        __m512i vMaxYA = _mm512_set1_epi32(maxYA);
        __m512i vMinZA = _mm512_set1_epi32(minZA);
        __m512i vMaxZA = _mm512_set1_epi32(maxZA);

        __m512i vMinXA = _mm512_set1_epi32(minXA);
        int writeOffset = i * K;
        int currentCollisions = 0;

        int* __restrict outA = outputA + writeOffset;
        int* __restrict outB = outputB + writeOffset;

        auto processRange = [&](int start, int end) AR_SIMD_TARGET
        {
            int j = start;

            int alignedStart = (j + 15) & ~15;
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

            const __mmask16 allOnes = 0xFFFFu;

            for (; j < end - 15; j += 16)
            {
                __m512i vMinXB = _mm512_load_si512(&pMinX[j]);
                auto vIsAllGreater = _mm512_cmp_epi32_mask(vMinXB, vMaxXA, _MM_CMPINT_GT);
                if (vIsAllGreater == 0xFFFFu) return;

                __m512i vMaxXB = _mm512_load_si512(&pMaxX[j]);
                auto maskX = _kand_mask16(_kxor_mask16(vIsAllGreater, allOnes), _mm512_cmp_epi32_mask(vMaxXB, vMinXA, _MM_CMPINT_GT));
                if (_ktestz_mask16_u8(maskX, maskX)) continue;

                __m512i vMinYB = _mm512_load_si512(&pMinY[j]);
                __m512i vMaxYB = _mm512_load_si512(&pMaxY[j]);
                auto maskY = _kand_mask16(_mm512_cmp_epi32_mask(vMaxYA, vMinYB, _MM_CMPINT_GT), _mm512_cmp_epi32_mask(vMaxYB, vMinYA, _MM_CMPINT_GT));
                auto maskXY = _kand_mask16(maskX, maskY);
                if (_ktestz_mask16_u8(maskXY, maskXY)) continue;

                __m512i vMinZB = _mm512_load_si512(&pMinZ[j]);
                __m512i vMaxZB = _mm512_load_si512(&pMaxZ[j]);
                auto maskZ = _kand_mask16(_mm512_cmp_epi32_mask(vMaxZA, vMinZB, _MM_CMPINT_GT), _mm512_cmp_epi32_mask(vMaxZB, vMinZA, _MM_CMPINT_GT));
                auto maskXYZ = _kand_mask16(maskXY, maskZ);
                if (_ktestz_mask16_u8(maskXYZ, maskXYZ)) continue;

                int cnt = __builtin_popcount(maskXYZ);
                const auto& bits = CompressTable<512>::instance.table[maskXYZ];
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
        // log("515");
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
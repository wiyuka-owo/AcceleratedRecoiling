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

        __m256i vMaxXA = _mm256_set1_epi32(maxXA);
        __m256i vMinYA = _mm256_set1_epi32(minYA);
        __m256i vMaxYA = _mm256_set1_epi32(maxYA);
        __m256i vMinZA = _mm256_set1_epi32(minZA);
        __m256i vMaxZA = _mm256_set1_epi32(maxZA);

        __m256i vMinXA = _mm256_set1_epi32(minXA);
        int writeOffset = i * K;
        int currentCollisions = 0;

        int* __restrict outA = outputA + writeOffset;
        int* __restrict outB = outputB + writeOffset;

        auto processRange = [&](int start, int end) AR_SIMD_TARGET
        {
            int j = start;

            int alignedStart = (j + 7) & ~7;
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

            const __m256i allOnes = _mm256_set1_epi32(-1);

            for (; j < end - 7; j += 8)
            {
                __m256i vMinXB = _mm256_load_si256((const __m256i*)&pMinX[j]);
                __m256i vIsAllGreater = _mm256_cmpgt_epi32(vMinXB, vMaxXA);
                if (_mm256_movemask_ps(_mm256_castsi256_ps(vIsAllGreater)) == 0xFF) return;

                __m256i vMaxXB = _mm256_load_si256((const __m256i*)&pMaxX[j]);
                __m256i maskX = _mm256_and_si256(_mm256_xor_si256(vIsAllGreater, allOnes), _mm256_cmpgt_epi32(vMaxXB, vMinXA));
                if (_mm256_testz_si256(maskX, maskX)) continue;

                __m256i vMinYB = _mm256_load_si256((const __m256i*)&pMinY[j]);
                __m256i vMaxYB = _mm256_load_si256((const __m256i*)&pMaxY[j]);
                __m256i maskY = _mm256_and_si256(_mm256_cmpgt_epi32(vMaxYA, vMinYB), _mm256_cmpgt_epi32(vMaxYB, vMinYA));
                __m256i maskXY = _mm256_and_si256(maskX, maskY);
                if (_mm256_testz_si256(maskXY, maskXY)) continue;

                __m256i vMinZB = _mm256_load_si256((const __m256i*)&pMinZ[j]);
                __m256i vMaxZB = _mm256_load_si256((const __m256i*)&pMaxZ[j]);
                __m256i maskZ = _mm256_and_si256(_mm256_cmpgt_epi32(vMaxZA, vMinZB), _mm256_cmpgt_epi32(vMaxZB, vMinZA));
                __m256i maskXYZ = _mm256_and_si256(maskXY, maskZ);
                if (_mm256_testz_si256(maskXYZ, maskXYZ)) continue;

                int laneMask = _mm256_movemask_ps(_mm256_castsi256_ps(maskXYZ));
                if (laneMask != 0)
                {
                    auto mask8 = static_cast<uint8_t>(laneMask);
                    int cnt = __builtin_popcount((unsigned int)mask8);
                    const auto& bits = CompressTable<256>::instance.table[mask8];
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
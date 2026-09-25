#include "batch_kernel.h"

#include <array>
#include <bit>
#include <cfloat>
#include <cmath>

#if (defined(__x86_64__) || defined(_M_X64)) && (defined(__clang__) || defined(__GNUC__))
#define AR_X86_SIMD 1

#include <immintrin.h>

#ifdef _MSC_VER
#include <intrin.h>
#else
#include <cpuid.h>
#endif
#endif

namespace ar {

std::int32_t quantize(double value) {
    const double scaled = std::floor(value * SCALE);
    if (scaled <= INT32_MIN) {
        return INT32_MIN;
    }
    if (scaled >= INT32_MAX) {
        return INT32_MAX;
    }

    return std::isnan(scaled) ? 0 : static_cast<std::int32_t>(scaled);
}

void quantizeBox(const double* bounds, std::int32_t* result) {
    for (int field = BOUNDS_MIN_X; field <= BOUNDS_MAX_Z; ++field) {
        if (std::isfinite(bounds[field])) {
            continue;
        }

        for (int bound = BOUNDS_MIN_X; bound <= BOUNDS_MAX_Z; ++bound) {
            result[bound] = bound < BOUNDS_MAX_X ? INT32_MIN : INT32_MAX;
        }
        return;
    }

    for (int field = BOUNDS_MIN_X; field <= BOUNDS_MAX_Z; ++field) {
        result[field] = quantize(bounds[field]);
    }
}

static bool scalarCandidate(const double* data, int stride, int section, int slot, int entries,
        std::int64_t* output, const Query& q, int& selected, int& nonzero) {
    if (section == q.sourceSection && slot == q.sourceSlot) {
        return true;
    }

    const bool intersects = data[BOUNDS_MIN_X * stride + slot] < q.bounds[BOUNDS_MAX_X]
            && data[BOUNDS_MAX_X * stride + slot] > q.bounds[BOUNDS_MIN_X]
            && data[BOUNDS_MIN_Y * stride + slot] < q.bounds[BOUNDS_MAX_Y]
            && data[BOUNDS_MAX_Y * stride + slot] > q.bounds[BOUNDS_MIN_Y]
            && data[BOUNDS_MIN_Z * stride + slot] < q.bounds[BOUNDS_MAX_Z]
            && data[BOUNDS_MAX_Z * stride + slot] > q.bounds[BOUNDS_MIN_Z];
    if (!intersects) {
        return true;
    }

    const double flag = data[FLAGS * stride + slot];
    if (flag < 0) {
        return false;
    }
    if (flag == 0) {
        return true;
    }

    const double x = data[POS_X * stride + slot];
    const double z = data[POS_Z * stride + slot];
    if (!std::isfinite(x) || !std::isfinite(z)) {
        return false;
    }

    const std::int64_t hit = (std::int64_t(section) << 32) | std::uint32_t(slot);
    output[selected++] = hit;

    constexpr double pushThreshold = static_cast<double>(0.01f);
    if (std::fabs(q.x - x) >= pushThreshold || std::fabs(q.z - z) >= pushThreshold) {
        output[entries + nonzero++] = hit;
    }

    return true;
}

static std::int64_t scalarBatch(const Section* plan, int sectionCount, int entries,
        std::int64_t* output, const Query& q) {
    int selected = 0;
    int nonzero = 0;

    for (int s = 0; s < sectionCount; ++s) {
        const auto* data = reinterpret_cast<const double*>(plan[s].address);
        for (int i = 0; i < plan[s].count; ++i) {
            if (!scalarCandidate(data, plan[s].stride, s, i, entries, output, q, selected, nonzero)) {
                return -1;
            }
        }
    }

    return (std::int64_t(selected) << 32) | std::uint32_t(nonzero);
}

struct CompressTable {
    std::array<std::array<std::uint8_t, 8>, 256> lanes{};

    constexpr CompressTable() {
        for (unsigned mask = 0; mask < 256; ++mask) {
            int count = 0;
            for (int bit = 0; bit < 8; ++bit) {
                if (mask & (1u << bit)) {
                    lanes[mask][count++] = bit;
                }
            }
        }
    }
};

alignas(64) static constexpr CompressTable compressTable;

static inline void storeMask(std::int64_t* output, std::int64_t first, unsigned mask) {
    const auto& lanes = compressTable.lanes[mask];
    const int count = std::popcount(mask);

    for (int i = 0; i < count; ++i) {
        output[i] = first + lanes[i];
    }
}

#ifdef AR_X86_SIMD

#define AR_TARGET __attribute__((target("sse2")))
#define AR_BATCH sse2Batch
#define AR_DLANES 2
#define AR_ILANES 4

#define AR_SETD _mm_set1_pd
#define AR_SETI _mm_set1_epi32
#define AR_LOADD _mm_loadu_pd
#define AR_LOADI(p) _mm_loadu_si128(reinterpret_cast<const __m128i*>(p))

#define AR_LT(a,b) unsigned(_mm_movemask_pd(_mm_cmplt_pd(a,b)))
#define AR_LE(a,b) unsigned(_mm_movemask_pd(_mm_cmple_pd(a,b)))
#define AR_EQ(a,b) unsigned(_mm_movemask_pd(_mm_cmpeq_pd(a,b)))
#define AR_IGT _mm_cmpgt_epi32
#define AR_IEQ _mm_cmpeq_epi32
#define AR_IOR _mm_or_si128
#define AR_IMASK(a) unsigned(_mm_movemask_ps(_mm_castsi128_ps(a)))

#define AR_ABS(a) _mm_andnot_pd(_mm_set1_pd(-0.0), a)
#define AR_SUB _mm_sub_pd
#define AR_STORE storeMask

#include "batch_simd.inc"
#include "batch_simd_undef.inc"

__attribute__((target("avx2")))
static inline void storeMask256(std::int64_t* output, std::int64_t first, unsigned mask) {
    if (mask == 15) {
        const auto ids = _mm256_add_epi64(_mm256_set1_epi64x(first), _mm256_set_epi64x(3, 2, 1, 0));
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(output), ids);
    } else {
        storeMask(output, first, mask);
    }
}

#define AR_TARGET __attribute__((target("avx2")))
#define AR_BATCH avx2Batch
#define AR_DLANES 4
#define AR_ILANES 8

#define AR_SETD _mm256_set1_pd
#define AR_SETI _mm256_set1_epi32
#define AR_LOADD _mm256_loadu_pd
#define AR_LOADI(p) _mm256_loadu_si256(reinterpret_cast<const __m256i*>(p))

#define AR_LT(a,b) unsigned(_mm256_movemask_pd(_mm256_cmp_pd(a,b,_CMP_LT_OQ)))
#define AR_LE(a,b) unsigned(_mm256_movemask_pd(_mm256_cmp_pd(a,b,_CMP_LE_OQ)))
#define AR_EQ(a,b) unsigned(_mm256_movemask_pd(_mm256_cmp_pd(a,b,_CMP_EQ_OQ)))
#define AR_IGT _mm256_cmpgt_epi32
#define AR_IEQ _mm256_cmpeq_epi32
#define AR_IOR _mm256_or_si256
#define AR_IMASK(a) unsigned(_mm256_movemask_ps(_mm256_castsi256_ps(a)))

#define AR_ABS(a) _mm256_andnot_pd(_mm256_set1_pd(-0.0), a)
#define AR_SUB _mm256_sub_pd
#define AR_STORE storeMask256

#include "batch_simd.inc"
#include "batch_simd_undef.inc"

__attribute__((target("avx512f,avx512dq,avx512bw,avx512vl")))
static inline void storeMask512(std::int64_t* output, std::int64_t first, unsigned mask) {
    const auto ids = _mm512_add_epi64(_mm512_set1_epi64(first), _mm512_set_epi64(7, 6, 5, 4, 3, 2, 1, 0));
    _mm512_mask_compressstoreu_epi64(output, static_cast<__mmask8>(mask), ids);
}

#define AR_TARGET __attribute__((target("avx512f,avx512dq,avx512bw,avx512vl")))
#define AR_BATCH avx512Batch
#define AR_DLANES 8
#define AR_ILANES 16

#define AR_SETD _mm512_set1_pd
#define AR_SETI _mm512_set1_epi32
#define AR_LOADD _mm512_loadu_pd
#define AR_LOADI(p) _mm512_loadu_si512(p)

#define AR_LT(a,b) unsigned(_mm512_cmp_pd_mask(a,b,_CMP_LT_OQ))
#define AR_LE(a,b) unsigned(_mm512_cmp_pd_mask(a,b,_CMP_LE_OQ))
#define AR_EQ(a,b) unsigned(_mm512_cmp_pd_mask(a,b,_CMP_EQ_OQ))
#define AR_IGT(a,b) unsigned(_mm512_cmpgt_epi32_mask(a,b))
#define AR_IEQ(a,b) unsigned(_mm512_cmpeq_epi32_mask(a,b))
#define AR_IOR(a,b) ((a) | (b))
#define AR_IMASK(a) (a)

#define AR_ABS(a) _mm512_andnot_pd(_mm512_set1_pd(-0.0), a)
#define AR_SUB _mm512_sub_pd
#define AR_STORE storeMask512

#include "batch_simd.inc"
#include "batch_simd_undef.inc"

static void cpuid(int leaf, int subleaf, int* result) {
#ifdef _MSC_VER
    __cpuidex(result, leaf, subleaf);
#else
    __cpuid_count(leaf, subleaf, result[0], result[1], result[2], result[3]);
#endif
}

__attribute__((target("xsave")))
static std::uint64_t xcr0() {
    return _xgetbv(0);
}

static Kernel cpuKernel() {
    static const Kernel detected = [] {
        int info[4];
        cpuid(0, 0, info);
        if (info[0] < 7) {
            return Kernel::SSE2;
        }

        cpuid(1, 0, info);
        const bool osSupportsXsave = (info[2] & (1 << 27)) != 0;
        const bool cpuSupportsAvx = (info[2] & (1 << 28)) != 0;
        if (!osSupportsXsave || !cpuSupportsAvx) {
            return Kernel::SSE2;
        }

        const auto state = xcr0();
        constexpr unsigned avxState = (1u << 1) | (1u << 2);
        constexpr unsigned avx512State = avxState | (1u << 5) | (1u << 6) | (1u << 7);
        if ((state & avxState) != avxState) {
            return Kernel::SSE2;
        }

        cpuid(7, 0, info);
        constexpr unsigned required512 = (1u << 16) | (1u << 17) | (1u << 30) | (1u << 31);
        if ((state & avx512State) == avx512State && (std::uint32_t(info[1]) & required512) == required512) {
            return Kernel::AVX512;
        }

        return (info[1] & (1 << 5)) != 0 ? Kernel::AVX2 : Kernel::SSE2;
    }();

    return detected;
}

#else

static Kernel cpuKernel() {
    return Kernel::Scalar;
}

#endif

bool supported(Kernel kernel) {
    if (kernel == Kernel::Scalar || kernel == Kernel::Auto) {
        return true;
    }

    int value = static_cast<int>(kernel);
    if (value >= 4) {
        value -= 3;
    }

    return value >= 1 && value <= static_cast<int>(cpuKernel());
}

Kernel bestKernel(bool quantized) {
    Kernel result = cpuKernel();
    return quantized && result != Kernel::Scalar
            ? static_cast<Kernel>(static_cast<int>(result) + 3) : result;
}

const char* kernelName(Kernel kernel) {
    switch (kernel) {
        case Kernel::Scalar:
            return "scalar";
        case Kernel::SSE2:
            return "sse2";
        case Kernel::AVX2:
            return "avx2";
        case Kernel::AVX512:
            return "avx512";
        case Kernel::QuantizedSSE2:
            return "quantized-sse2";
        case Kernel::QuantizedAVX2:
            return "quantized-avx2";
        case Kernel::QuantizedAVX512:
            return "quantized-avx512";
        default:
            return "auto";
    }
}

std::int64_t batch(Kernel kernel, const Section* plan, int sectionCount, int entries,
        std::int64_t* output, const Query& q) {
    if (!std::isfinite(q.x) || !std::isfinite(q.z)) {
        return -1;
    }

    for (double bound : q.bounds) {
        if (!std::isfinite(bound)) {
            return scalarBatch(plan, sectionCount, entries, output, q);
        }
    }

    if (static_cast<int>(kernel) >= 4) {
        bool hasQuantizedSection = false;
        for (int s = 0; s < sectionCount; ++s) {
            hasQuantizedSection |= plan[s].count >= MIN_QUANTIZED_ENTITIES;
        }
        if (!hasQuantizedSection) {
            kernel = static_cast<Kernel>(static_cast<int>(kernel) - 3);
        }
    }

#ifdef AR_X86_SIMD
    switch (kernel) {
        case Kernel::SSE2:
            return sse2Batch<false>(plan, sectionCount, entries, output, q);
        case Kernel::AVX2:
            return avx2Batch<false>(plan, sectionCount, entries, output, q);
        case Kernel::AVX512:
            return avx512Batch<false>(plan, sectionCount, entries, output, q);
        case Kernel::QuantizedSSE2:
            return sse2Batch<true>(plan, sectionCount, entries, output, q);
        case Kernel::QuantizedAVX2:
            return avx2Batch<true>(plan, sectionCount, entries, output, q);
        case Kernel::QuantizedAVX512:
            return avx512Batch<true>(plan, sectionCount, entries, output, q);
        default:
            break;
    }
#endif

    return scalarBatch(plan, sectionCount, entries, output, q);
}

}

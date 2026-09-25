#pragma once

#include <cstdint>

namespace ar {

enum EntityFieldOffset : int {
    BOUNDS_MIN_X = 0,
    BOUNDS_MIN_Y = 1,
    BOUNDS_MIN_Z = 2,
    BOUNDS_MAX_X = 3,
    BOUNDS_MAX_Y = 4,
    BOUNDS_MAX_Z = 5,
    POS_X = 6,
    POS_Z = 7,
    FLAGS = 8,
    QUANTIZED_BOXES = 9
};

struct Section {
    std::int64_t address;
    std::int32_t count;
    std::int32_t stride;
};
static_assert(sizeof(Section) == 16);

struct Query {
    double bounds[6];
    double x;
    double z;
    int sourceSection;
    int sourceSlot;
};

enum class Kernel : int {
    Auto = -1,
    Scalar,
    SSE2,
    AVX2,
    AVX512,
    QuantizedSSE2,
    QuantizedAVX2,
    QuantizedAVX512
};

constexpr double SCALE = 64.0;
constexpr int MIN_QUANTIZED_ENTITIES = 1024;

std::int32_t quantize(double value);

void quantizeBox(const double* bounds, std::int32_t* result);

bool supported(Kernel kernel);

Kernel bestKernel(bool quantized = true);

const char* kernelName(Kernel kernel);

std::int64_t batch(Kernel kernel, const Section* plan, int sectionCount, int entries, std::int64_t* output, const Query& query);

}

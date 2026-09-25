#include "batch_kernel.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string_view>
#include <vector>

struct Input {
    int count;
    int stride;
    std::vector<double> doubles;
    explicit Input(int size, int padding = 0) : count(size), stride(std::max(1, size + padding)),
            doubles((size >= ar::MIN_QUANTIZED_ENTITIES ? 12 : 9) * stride) {}
    ar::Section section() const {
        return {reinterpret_cast<std::int64_t>(doubles.data()), count, stride};
    }
    void put(int slot, const std::array<double, 6>& box, double x, double z, double flag = 1) {
        for (int plane = 0; plane < 6; ++plane) {
            doubles[plane * stride + slot] = box[plane];
        }
        doubles[ar::POS_X * stride + slot] = x;
        doubles[ar::POS_Z * stride + slot] = z;
        doubles[ar::FLAGS * stride + slot] = flag;
        if (count < ar::MIN_QUANTIZED_ENTITIES) return;
        std::int32_t quantized[6];
        ar::quantizeBox(box.data(), quantized);
        auto* bytes = reinterpret_cast<char*>(doubles.data() + ar::QUANTIZED_BOXES * stride);
        for (int plane = 0; plane < 6; ++plane) {
            const int offset = (plane * stride + slot) * sizeof(std::int32_t);
            std::memcpy(bytes + offset, &quantized[plane], sizeof(std::int32_t));
        }
    }
};

static constexpr std::int64_t sentinel = 0x1937562840293746;
static std::uint64_t checks;
static std::vector<ar::Kernel> kernels;

static void check(const std::vector<Input>& inputs, const ar::Query& query, int caseNumber) {
    std::vector<ar::Section> plan;
    int count = 0;
    for (const auto& input : inputs) {
        plan.push_back(input.section());
        count += input.count;
    }
    std::vector<std::int64_t> reference(2 * count + 4, sentinel);
    std::vector<std::int64_t> observed(reference.size());
    const int sectionCount = static_cast<int>(plan.size());
    const auto expected = ar::batch(ar::Kernel::Scalar, plan.data(), sectionCount, count, reference.data() + 2, query);
    for (auto kernel : kernels) {
        std::fill(observed.begin(), observed.end(), sentinel);
        const auto result = ar::batch(kernel, plan.data(), sectionCount, count, observed.data() + 2, query);
        const bool guardsIntact = observed[0] == sentinel && observed[1] == sentinel
                && observed[2 * count + 2] == sentinel && observed[2 * count + 3] == sentinel;
        const bool outputsMatch = result < 0 || reference == observed;
        if (result != expected || !outputsMatch || !guardsIntact) {
            throw std::runtime_error("Mismatch case=" + std::to_string(caseNumber) + " kernel=" + ar::kernelName(kernel));
        }
        ++checks;
    }
}

static constexpr double infinity = std::numeric_limits<double>::infinity();
static constexpr double pushThreshold = static_cast<double>(0.01f);

static void checkQuantizedBox(const std::array<double, 6>& bounds,
        const std::array<std::int32_t, 6>& expected) {
    std::array<std::int32_t, 6> result;
    result.fill(123456789);
    ar::quantizeBox(bounds.data(), result.data());

    if (result != expected) {
        throw std::runtime_error("Incorrect box quantization");
    }
}

static void boxQuantization() {
    const std::array<double, 6> bounds{-1.01, -0.01, -0.0, 1.01, 0.01, 2.5};
    const std::array<std::int32_t, 6> fullRange{
        INT32_MIN, INT32_MIN, INT32_MIN, INT32_MAX, INT32_MAX, INT32_MAX
    };

    checkQuantizedBox(bounds, {-65, -1, 0, 64, 0, 160});
    checkQuantizedBox({-1e100, -1e308, -33554432, 1e100, 1e308, 33554432}, fullRange);
    checkQuantizedBox({infinity, infinity, infinity, infinity, infinity, infinity}, fullRange);

    for (double invalid : {infinity, -infinity, std::numeric_limits<double>::quiet_NaN()}) {
        for (int field = ar::BOUNDS_MIN_X; field <= ar::BOUNDS_MAX_Z; ++field) {
            auto invalidBounds = bounds;
            invalidBounds[field] = invalid;
            checkQuantizedBox(invalidBounds, fullRange);
        }
    }

    std::cout << "PASS finite, saturated and non-finite box quantization\n";
}

static double randomPosition(std::mt19937_64& random) {
    return static_cast<double>(int(random() % 512) - 256) / ar::SCALE;
}

static double specialCoordinate(std::mt19937_64& random) {
    static constexpr std::array values{
        0.0, -0.0, 1.0 / ar::SCALE, -1.0 / ar::SCALE,
        0.01, pushThreshold, -0.01,
        29999999.99, -29999999.99, 33554432.0, -33554432.0,
        1e100, -1e100,
        std::numeric_limits<double>::max(), -std::numeric_limits<double>::max(),
        infinity, -infinity, std::numeric_limits<double>::quiet_NaN(),
        std::numeric_limits<double>::denorm_min()
    };
    return values[random() % values.size()];
}

static int sectionSize(std::mt19937_64& random, int caseIndex, int sectionIndex) {
    if (caseIndex < 200) return caseIndex % 70;
    const bool nearQuantizationThreshold = (caseIndex + sectionIndex) % 4 == 1;
    if (nearQuantizationThreshold) {
        return ar::MIN_QUANTIZED_ENTITIES - 1 + random() % 67;
    }
    return random() % 100;
}

static void fillSection(Input& input, const ar::Query& query, double origin,
        int caseIndex, std::mt19937_64& random) {
    const bool gridPositions = caseIndex % 2 == 0;
    const bool specialBounds = caseIndex % 13 == 0;
    const bool specialPositions = caseIndex % 17 == 0;
    const bool nearPushThreshold = caseIndex % 19 == 0;
    const bool includeCallbacks = caseIndex % 7 == 0;
    const bool includeRejected = caseIndex % 3 == 0;

    for (int slot = 0; slot < input.count; ++slot) {
        const double offsetX = gridPositions ? 0.01 * int(random() % 40) : randomPosition(random);
        double x = origin + offsetX;
        double z = origin + 0.01 * int(random() % 40);
        std::array<double, 6> box{x - .45, origin - .45, z - .45, x + .45, origin + .45, z + .45};

        const bool besideQueryEdge = slot % 5 == 0;
        const bool touchingQueryEdge = slot % 7 == 0;
        const bool zeroWidth = slot % 11 == 0;
        const double direction = slot % 2 == 0 ? -infinity : infinity;
        if (besideQueryEdge) {
            box[ar::BOUNDS_MIN_X] = std::nextafter(query.bounds[ar::BOUNDS_MAX_X], direction);
        }
        if (touchingQueryEdge) {
            box[ar::BOUNDS_MIN_X] = query.bounds[ar::BOUNDS_MAX_X];
        }
        if (zeroWidth) {
            box[ar::BOUNDS_MAX_X] = box[ar::BOUNDS_MIN_X];
        }
        if (specialBounds) {
            const double coordinate = specialCoordinate(random);
            box[random() % box.size()] = coordinate;
        }
        if (specialPositions && slot % 3 == 0) {
            x = specialCoordinate(random);
        }
        if (nearPushThreshold) {
            x = std::nextafter(origin + pushThreshold, direction);
            z = origin;
        }

        double flag = 1;
        if (includeCallbacks && slot % 9 == 0) {
            flag = -1;
        } else if (includeRejected && slot % 3 == 0) {
            flag = 0;
        }
        input.put(slot, box, x, z, flag);
    }
}

static void differential() {
    std::mt19937_64 random(20260925);
    constexpr int caseCount = 24000;
    for (int caseIndex = 0; caseIndex < caseCount; ++caseIndex) {
        const bool specialOrigin = caseIndex % 4 == 0;
        const bool specialQueryBounds = caseIndex % 29 == 0;
        const bool specialQueryPosition = caseIndex % 31 == 0;
        const double origin = specialOrigin ? specialCoordinate(random) : randomPosition(random);
        ar::Query query{{-.5, -.5, -.5, .5, .5, .5}, origin, origin, -1, -1};
        for (double& bound : query.bounds) {
            bound += origin;
        }
        if (specialQueryBounds) {
            const double coordinate = specialCoordinate(random);
            query.bounds[random() % 6] = coordinate;
        }
        if (specialQueryPosition) {
            query.x = specialCoordinate(random);
        }

        std::vector<Input> inputs;
        const int sectionCount = 1 + caseIndex % 3;
        for (int section = 0; section < sectionCount; ++section) {
            const int size = sectionSize(random, caseIndex, section);
            inputs.emplace_back(size, random() % 5);
            fillSection(inputs.back(), query, origin, caseIndex, random);
        }
        const bool excludeSource = caseIndex % 3 != 0;
        if (excludeSource) {
            query.sourceSection = caseIndex % sectionCount;
            query.sourceSlot = caseIndex % std::max(1, inputs[query.sourceSection].count);
        }
        check(inputs, query, caseIndex);
    }
    check({}, ar::Query{{0, 0, 0, 1, 1, 1}, 0, 0, -1, -1}, caseCount);
    std::cout << "PASS " << checks << " kernel/oracle comparisons (ordered outputs, counts, fallback and guards)\n";
}

static void benchmark() {
    using Clock = std::chrono::steady_clock;
    std::cout << "workload,kernel,entities,medianNsPerQuery,checksum\n";
    for (std::string_view workload : {"coincident", "moving", "sparse", "boundary"}) {
        Input input(3072);
        for (int i = 0; i < input.count; ++i) {
            double x = 0;
            double z = 0;
            if (workload == "moving") {
                x = (i % 8) * .06;
                z = (i / 8 % 8) * .06;
            } else if (workload != "coincident") {
                x = (i % 64) * .35;
                z = (i / 64) * .35;
            }
            auto box = std::array<double, 6>{x - .45, -.45, z - .45, x + .45, .45, z + .45};
            if (workload == "boundary") {
                box[ar::BOUNDS_MIN_X] = std::nextafter(.45, i % 2 ? 1.0 : 0.0);
            }
            input.put(i, box, x, z);
        }
        auto section = input.section();
        std::vector<std::int64_t> output(input.count * 2);
        for (auto kernel : kernels) {
            std::vector<double> times;
            std::uint64_t checksum = 0;
            for (int round = -2; round < 9; ++round) {
                auto start = Clock::now();
                for (int i = 0; i < 600; ++i) {
                    const int slot = i % 32;
                    double x = input.doubles[ar::POS_X * input.stride + slot];
                    double z = input.doubles[ar::POS_Z * input.stride + slot];
                    ar::Query query{{x - .45, -.45, z - .45, x + .45, .45, z + .45}, x, z, 0, slot};
                    auto result = ar::batch(kernel, &section, 1, input.count, output.data(), query);
                    checksum += static_cast<std::uint64_t>(result) + output[0];
                }
                if (round >= 0) {
                    const double elapsed = std::chrono::duration<double, std::nano>(Clock::now() - start).count();
                    times.push_back(elapsed / 600);
                }
            }
            std::sort(times.begin(), times.end());
            std::cout << workload << ',' << ar::kernelName(kernel) << ',' << input.count << ','
                    << times[times.size() / 2] << ',' << checksum << '\n';
        }
    }
}

int main(int argc, char**) {
    try {
        std::cout << "auto=" << ar::kernelName(ar::bestKernel()) << '\n';
        for (int i = 0; i <= 6; ++i) {
            auto kernel = static_cast<ar::Kernel>(i);
            if (ar::supported(kernel)) kernels.push_back(kernel);
        }
        boxQuantization();
        differential();
        if (argc > 1) benchmark();
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}

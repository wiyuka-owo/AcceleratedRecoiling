#include <jni.h>
#include <cstdint>
#include <cmath>

#include "batch_kernel.h"

using ar::Section;

static ar::Kernel batchKernel = ar::bestKernel();

extern "C" {

JNIEXPORT jint JNICALL Java_com_wiyuka_acceleratedrecoiling_natives_realtime_RealtimeNative_version(
        JNIEnv*, jclass) {
    return 7;
}

JNIEXPORT jint JNICALL Java_com_wiyuka_acceleratedrecoiling_natives_realtime_RealtimeNative_quantizationMinEntities(
        JNIEnv*, jclass) {
    return ar::MIN_QUANTIZED_ENTITIES;
}

JNIEXPORT jint JNICALL Java_com_wiyuka_acceleratedrecoiling_natives_realtime_RealtimeNative_selectKernel(
        JNIEnv*, jclass, jint requested) {
    auto kernel = static_cast<ar::Kernel>(requested);
    if (requested == -1) {
        kernel = ar::bestKernel();
    } else if (requested == -2) {
        kernel = ar::bestKernel(false);
    }
    if (!ar::supported(kernel)) {
        return -1;
    }

    batchKernel = kernel;
    return static_cast<jint>(kernel);
}

JNIEXPORT jlong JNICALL Java_com_wiyuka_acceleratedrecoiling_natives_realtime_RealtimeNative_address(
        JNIEnv* env, jclass, jobject buffer) {
    return reinterpret_cast<jlong>(env->GetDirectBufferAddress(buffer));
}

JNIEXPORT jlong JNICALL Java_com_wiyuka_acceleratedrecoiling_natives_realtime_RealtimeNative_queryBatch(
        JNIEnv* env, jclass, jobject sectionBuffer, jint sectionCount, jobject outputBuffer,
        jint sourceSection, jint sourceSlot, jdouble sourceX, jdouble sourceZ,
        jdouble minX, jdouble minY, jdouble minZ, jdouble maxX, jdouble maxY, jdouble maxZ) {
    const auto* sections = static_cast<const Section*>(env->GetDirectBufferAddress(sectionBuffer));
    auto* output = static_cast<std::int64_t*>(env->GetDirectBufferAddress(outputBuffer));

    if (!sections || !output || sectionCount < 0) {
        return -2;
    }

    const auto requiredSectionBytes = std::int64_t(sectionCount) * std::int64_t(sizeof(Section));
    if (requiredSectionBytes > env->GetDirectBufferCapacity(sectionBuffer)) {
        return -2;
    }

    if (!std::isfinite(sourceX) || !std::isfinite(sourceZ)) {
        return -1;
    }

    std::int64_t entryCount = 0;
    for (int section = 0; section < sectionCount; ++section) {
        const auto& currentSection = sections[section];
        if (!currentSection.address || currentSection.count < 0 || currentSection.stride < currentSection.count) {
            return -2;
        }

        entryCount += currentSection.count;
    }

    if (entryCount > INT32_MAX) {
        return -2;
    }

    const std::int64_t requiredOutputBytes = entryCount * 2 * sizeof(std::int64_t);
    if (requiredOutputBytes > env->GetDirectBufferCapacity(outputBuffer)) {
        return -2;
    }

    ar::Query query{
        {minX, minY, minZ, maxX, maxY, maxZ},
        sourceX, sourceZ,
        sourceSection, sourceSlot
    };

    return ar::batch(batchKernel, sections, sectionCount, static_cast<int>(entryCount), output, query);
}

}

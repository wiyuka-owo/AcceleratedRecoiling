package com.wiyuka.acceleratedrecoiling.natives.realtime;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class RealtimeNative {
    private static final int NATIVE_ABI_VERSION = 7;

    private static boolean attempted;
    private static volatile boolean loaded;
    private static Kernel kernel = Kernel.SCALAR;
    private static int quantizationThreshold;

    private RealtimeNative() {
    }

    public static synchronized void initialize() {
        if (attempted) {
            return;
        }
        attempted = true;
        try {
            String name = System.mapLibraryName("AcceleratedRecoilingRealtime");
            String resource = platformPath() + name;
            var file = Files.createTempFile("ar-realtime-", "-" + name);
            file.toFile().deleteOnExit();
            try (var in = RealtimeNative.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Native collision library missing: " + resource);
                }
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            System.load(file.toAbsolutePath().toString());
            if (version() != NATIVE_ABI_VERSION) {
                throw new IllegalStateException("Unsupported native collision ABI");
            }

            Kernel requested = Kernel.fromName(System.getProperty("ar.realtime.kernel", Kernel.AUTO.configName));
            int selectedKernel = selectKernel(requested.nativeId);
            if (selectedKernel < 0) {
                throw new IllegalStateException("Requested kernel is unsupported by CPU/OS: " + requested.configName);
            }
            kernel = Kernel.fromNativeId(selectedKernel);
            quantizationThreshold = quantizationMinEntities();
            loaded = true;
            AcceleratedRecoiling.LOGGER.info("NATIVE_BATCHED initialized: {}", kernelName());
        } catch (IOException | RuntimeException | LinkageError e) {
            AcceleratedRecoiling.LOGGER.warn("Native collision initialization failed; using vanilla collisions", e);
        }
    }

    public static boolean isAvailable() {
        return loaded;
    }

    public static boolean isEnabled() {
        return loaded && FoldConfig.enableEntityCollision;
    }

    private static String platformPath() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String os;
        if (osName.startsWith("windows")) {
            os = "windows";
        } else if (osName.contains("mac")) {
            os = "macos";
        } else if (osName.contains("linux")) {
            os = "linux";
        } else {
            throw new UnsupportedOperationException("Unsupported native platform");
        }
        String arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (arch == null) {
            throw new UnsupportedOperationException("Unsupported native platform");
        }
        return "/natives/" + os + "-" + arch + "/";
    }

    private static native int version();
    private static native int selectKernel(int requested);
    private static native int quantizationMinEntities();

    static boolean quantizeSection(int size) {
        return kernel.quantized && size >= quantizationThreshold;
    }

    public static String kernelName() {
        return kernel.configName;
    }

    static native long address(ByteBuffer buffer);

    static native long queryBatch(ByteBuffer sectionDescriptors, int sectionCount, ByteBuffer output,
            int sourceSection, int sourceSlot, double sourceX, double sourceZ,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    private enum Kernel {
        AUTO(-1, "auto", false),
        SIMD(-2, "simd", false),
        SCALAR(0, "scalar", false),
        SSE2(1, "sse2", false),
        AVX2(2, "avx2", false),
        AVX512(3, "avx512", false),
        QUANTIZED_SSE2(4, "quantized-sse2", true),
        QUANTIZED_AVX2(5, "quantized-avx2", true),
        QUANTIZED_AVX512(6, "quantized-avx512", true);

        private final int nativeId;
        private final String configName;
        private final boolean quantized;

        Kernel(int nativeId, String configName, boolean quantized) {
            this.nativeId = nativeId;
            this.configName = configName;
            this.quantized = quantized;
        }

        static Kernel fromName(String name) {
            for (Kernel kernel : values()) {
                if (kernel.configName.equals(name)) {
                    return kernel;
                }
            }
            throw new IllegalArgumentException("Unknown realtime kernel: " + name);
        }

        static Kernel fromNativeId(int nativeId) {
            for (Kernel kernel : values()) {
                if (kernel.nativeId == nativeId) {
                    return kernel;
                }
            }
            throw new IllegalStateException("Unknown native kernel: " + nativeId);
        }
    }
}

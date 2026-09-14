package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionConfig;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionEngine;
import com.wiyuka.acceleratedrecoiling.algorithm.CollisionResult;
import com.wiyuka.acceleratedrecoiling.algorithm.FfmCollisionEngine;
import com.wiyuka.acceleratedrecoiling.algorithm.GpuCollisionEngine;
import com.wiyuka.acceleratedrecoiling.algorithm.JavaCollisionEngine;
import com.wiyuka.acceleratedrecoiling.config.FoldConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class NativeInterface {
    public static boolean isVectorApiAvailable() {
        try {
            Class.forName("jdk.incubator.vector.Vector");
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static String getPlatformNativePath() {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String os;
        if (osName.contains("win")) {
            os = "windows";
        } else if (osName.contains("mac")) {
            os = "macos";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            os = "linux";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + osName);
        }
        String arch;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            arch = "x64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            arch = "arm64";
        } else {
            throw new UnsupportedOperationException("Unsupported architecture: " + osArch);
        }
        return "/natives/" + os + "-" + arch + "/";
    }

    public enum BackendType {
        FFM("FFM", () -> new FfmCollisionEngine(NativeLibraryLoader.extract())),
        JNI("JNI", () -> new JNIBackend(NativeLibraryLoader.extract())),
        JAVA_SIMD("Java SIMD", () -> {
            if (!isVectorApiAvailable()) throw new UnsupportedOperationException("Vector API not available");
            try {
                return (CollisionEngine) Class.forName("com.wiyuka.acceleratedrecoiling.algorithm.JavaSimdCollisionEngine")
                        .getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }),
        JAVA("Pure Java", JavaCollisionEngine::new),
        JAVA_VANILLA("Java Vanilla", JavaVanillaCollisionEngine::new),
        GPU("GPU", GpuCollisionEngine::new),
        AUTO("Auto", null);

        private final String displayName;
        private final Supplier<CollisionEngine> loader;

        BackendType(String displayName, Supplier<CollisionEngine> loader) {
            this.displayName = displayName;
            this.loader = loader;
        }

        public String getDisplayName() {
            return displayName;
        }

        public CollisionEngine tryLoad() {
            if (this == AUTO) return null;
            try {
                AcceleratedRecoiling.LOGGER.info("Attempting to load {} backend...", this.displayName);
                CollisionEngine instance = loader.get();
                instance.setConfig(currentConfig());
                instance.initialize();
                return instance;
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                AcceleratedRecoiling.LOGGER.warn("{} backend failed to load. Reason: {}", this.displayName, root.getMessage());
                return null;
            }
        }
    }

    private static CollisionEngine backend;
    private static boolean isInitialized = false;

    private static final List<BackendType> AUTO_FALLBACK_CHAIN = Arrays.asList(
            BackendType.JAVA_VANILLA,
            BackendType.GPU,
            BackendType.FFM,
            BackendType.JNI,
            BackendType.JAVA_SIMD,
            BackendType.JAVA
    );

    public static void initialize() {
        BackendType selectedBackend = parseBackend(FoldConfig.backend);
        if (selectedBackend != BackendType.AUTO) {
            AcceleratedRecoiling.LOGGER.info("User requested backend via config: {}", selectedBackend.getDisplayName());
        }
        if (selectedBackend == BackendType.AUTO) {
            if (AVX2.hasAVX2()) {
                selectedBackend = BackendType.FFM;
            } else if (isVectorApiAvailable()) {
                selectedBackend = BackendType.JAVA_SIMD;
            } else {
                selectedBackend = BackendType.JAVA;
            }
            AcceleratedRecoiling.LOGGER.info("Auto-selected backend: {}", selectedBackend.getDisplayName());
        }
        initialize(selectedBackend);
    }

    private static BackendType parseBackend(String raw) {
        if (raw == null || raw.isBlank()) {
            return BackendType.AUTO;
        }
        String trimmed = raw.trim();
        String normalized = trimmed.toUpperCase().replace(' ', '_').replace('-', '_');
        try {
            return BackendType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
        }
        for (BackendType type : BackendType.values()) {
            if (type.getDisplayName().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        AcceleratedRecoiling.LOGGER.warn("Unknown backend '{}' specified in config. Falling back to AUTO.", raw);
        return BackendType.AUTO;
    }

    public static void initialize(BackendType preferredType) {
        if (isInitialized) return;

        AcceleratedRecoiling.LOGGER.info("Initializing NativeInterface with preferred backend: {}", preferredType);

        backend = getBackend(preferredType);

        if (backend != null) {
            AcceleratedRecoiling.LOGGER.info("Successfully selected and initialized backend: {}", backend.getName());
            isInitialized = true;
        } else {
            throw new IllegalStateException("Failed to initialize ANY backend!");
        }
    }

    private static CollisionConfig currentConfig() {
        return new CollisionConfig(
                FoldConfig.maxCollision,
                FoldConfig.gridSize,
                FoldConfig.densityWindow,
                FoldConfig.maxThreads,
                FoldConfig.gpuIndex
        );
    }

    private static CollisionEngine getBackend(BackendType preferredType) {
        CollisionEngine instance = null;

        if (preferredType != BackendType.AUTO) {
            instance = preferredType.tryLoad();
            if (instance != null) return instance;

            AcceleratedRecoiling.LOGGER.warn("Preferred {} backend failed. Falling back to AUTO chain...", preferredType.getDisplayName());
        }

        AcceleratedRecoiling.LOGGER.info("Detected Java Version: {}", Runtime.version().feature());

        for (BackendType type : AUTO_FALLBACK_CHAIN) {
            if (type == preferredType) continue;

            if (type == BackendType.FFM && Runtime.version().feature() < 21) continue;

            instance = type.tryLoad();
            if (instance != null) return instance;
        }

        return null;
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    public static boolean isJavaVanilla() {
        return backend instanceof JavaVanillaCollisionEngine;
    }

    public static List<Entity> javaVanillaNeighbors(Entity entity, AABB box) {
        if (backend instanceof JavaVanillaCollisionEngine vanilla) {
            return vanilla.neighbors(entity, box, ParallelAABB.getTickEntities());
        }
        return List.of();
    }

    public static void rebuildJavaVanilla(List<Entity> entities) {
        if (backend instanceof JavaVanillaCollisionEngine vanilla) {
            vanilla.rebuild(entities);
        }
    }

    public static void relocateJavaVanilla(Entity entity) {
        if (backend instanceof JavaVanillaCollisionEngine vanilla) {
            vanilla.relocate(entity);
        }
    }

    public static void applyConfig() {
        if (backend != null) {
            backend.setConfig(currentConfig());
        }
    }

    public static void destroy() {
        if (backend != null) {
            backend.destroy();
            backend = null;
        }
        isInitialized = false;
        ParallelAABB.isInitialized = false;
        ParallelAABB.clearTickEntities();
    }

    public static CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        if (backend == null) {
            resultSizeOut[0] = 0;
            return null;
        }
        return backend.push(locations, aabb, resultSizeOut);
    }
}

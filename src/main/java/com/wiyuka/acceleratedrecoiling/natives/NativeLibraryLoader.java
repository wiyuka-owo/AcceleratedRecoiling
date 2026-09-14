package com.wiyuka.acceleratedrecoiling.natives;

import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NativeLibraryLoader {
    private static Path extracted;

    private NativeLibraryLoader() {
    }

    public static Path extract() {
        if (extracted != null && Files.isRegularFile(extracted)) {
            return extracted;
        }
        String fullDllName = System.mapLibraryName("AcceleratedRecoiling");
        String resourcePath = NativeInterface.getPlatformNativePath() + fullDllName;
        try (InputStream in = AcceleratedRecoiling.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Native library not found: " + resourcePath);
            }
            Path tempFile = Files.createTempFile("AcceleratedRecoiling-", "-" + fullDllName);
            tempFile.toFile().deleteOnExit();
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            AcceleratedRecoiling.LOGGER.info("Extracted native library from {} to temp: {}", resourcePath, tempFile);
            extracted = tempFile;
            return tempFile;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract native library: " + resourcePath, e);
        }
    }
}

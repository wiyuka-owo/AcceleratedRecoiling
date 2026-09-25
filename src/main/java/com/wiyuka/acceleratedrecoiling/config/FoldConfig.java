package com.wiyuka.acceleratedrecoiling.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FoldConfig {
    public static volatile boolean enableEntityCollision = true;
    private static final Path CONFIG_FILE = Path.of("acceleratedRecoiling.json");

    private FoldConfig() {
    }

    public static void loadConfig() {
        boolean enabled = true;
        if (Files.exists(CONFIG_FILE)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(CONFIG_FILE)).getAsJsonObject();
                if (json.has("enableEntityCollision")) {
                    enabled = json.get("enableEntityCollision").getAsBoolean();
                }
            } catch (IOException | RuntimeException e) {
                AcceleratedRecoiling.LOGGER.warn("Cannot read collision config; using defaults", e);
            }
        }
        enableEntityCollision = enabled;
        saveConfig();
    }

    public static void saveConfig() {
        JsonObject json = new JsonObject();
        json.addProperty("enableEntityCollision", enableEntityCollision);
        try {
            Files.writeString(CONFIG_FILE, new GsonBuilder().setPrettyPrinting().create().toJson(json));
        } catch (IOException e) {
            AcceleratedRecoiling.LOGGER.error("Cannot save collision config", e);
        }
    }
}

package com.wiyuka.acceleratedrecoiling.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FoldConfig {
    public static final boolean debugDensity = false;
    public static boolean enableEntityCollision = true;
    public static boolean enableEntityGetterOptimization = true;
    public static int maxCollision = 32;
    public static int gridSize = 1;
    public static int densityWindow = 4;
    public static int densityThreshold = -1;
    public static int maxThreads = 1;
    public static String backend = "AUTO";
    private static final File CONFIG_FILE = new File("acceleratedRecoiling.json");

    public static void saveConfig() {
        Logger logger = AcceleratedRecoiling.LOGGER;
        try {
            Files.writeString(CONFIG_FILE.toPath(), toPrettyJson(currentJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Cannot save config file", e);
        }
    }

    public static void loadConfig() {
        Logger logger = AcceleratedRecoiling.LOGGER;
        String defaultConfigStr = toPrettyJson(defaultJson());

        if (!CONFIG_FILE.exists()) {
            try {
                if (CONFIG_FILE.createNewFile()) {
                    Files.writeString(CONFIG_FILE.toPath(), defaultConfigStr);
                }
            } catch (IOException e) {
                logger.error("Cannot create config file", e);
            }
        }

        String configFileContent;
        try {
            configFileContent = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read config, reason: {}. Using default.", e.getMessage());
            configFileContent = defaultConfigStr;
        }

        try {
            JsonObject configJson = JsonParser.parseString(configFileContent).getAsJsonObject();
            applyJson(configJson);
        } catch (Exception e) {
            logger.warn("Config broken: {}. Overwriting.", e.getMessage());
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultConfigStr);
            } catch (IOException ignored) {
            }
            applyJson(JsonParser.parseString(defaultConfigStr).getAsJsonObject());
        }

        logger.info("Configuration loaded successfully");
    }

    private static void applyJson(JsonObject configJson) {
        if (configJson.has("enableEntityCollision")) enableEntityCollision = configJson.get("enableEntityCollision").getAsBoolean();
        if (configJson.has("enableEntityGetterOptimization")) enableEntityGetterOptimization = configJson.get("enableEntityGetterOptimization").getAsBoolean();
        if (configJson.has("maxCollision")) maxCollision = configJson.get("maxCollision").getAsInt();
        if (configJson.has("gridSize")) gridSize = configJson.get("gridSize").getAsInt();
        if (configJson.has("densityWindow")) densityWindow = configJson.get("densityWindow").getAsInt();
        if (configJson.has("densityThreshold")) densityThreshold = configJson.get("densityThreshold").getAsInt();
        if (configJson.has("maxThreads")) maxThreads = configJson.get("maxThreads").getAsInt();
        if (configJson.has("backend")) backend = configJson.get("backend").getAsString();
    }

    private static JsonObject currentJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enableEntityCollision", enableEntityCollision);
        json.addProperty("enableEntityGetterOptimization", enableEntityGetterOptimization);
        json.addProperty("maxCollision", maxCollision);
        json.addProperty("gridSize", gridSize);
        json.addProperty("densityWindow", densityWindow);
        json.addProperty("densityThreshold", densityThreshold);
        json.addProperty("maxThreads", maxThreads);
        json.addProperty("backend", backend);
        return json;
    }

    private static JsonObject defaultJson() {
        JsonObject json = new JsonObject();
        json.addProperty("enableEntityCollision", true);
        json.addProperty("enableEntityGetterOptimization", true);
        json.addProperty("maxCollision", 32);
        json.addProperty("gridSize", 1);
        json.addProperty("densityWindow", 4);
        json.addProperty("densityThreshold", -1);
        json.addProperty("maxThreads", 1);
        json.addProperty("backend", "AUTO");
        return json;
    }

    private static String toPrettyJson(JsonObject json) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(json);
    }
}

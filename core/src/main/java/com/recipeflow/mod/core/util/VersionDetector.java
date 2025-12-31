package com.recipeflow.mod.core.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects modpack version from common modpack formats.
 * Supports CurseForge (manifest.json) and Modrinth (pack.toml).
 */
public class VersionDetector {

    private static final Logger LOGGER = Logger.getLogger(VersionDetector.class.getName());
    private static final Gson GSON = new Gson();

    private VersionDetector() {
        // Utility class
    }

    /**
     * Detect modpack version from the game directory.
     * Checks multiple locations in priority order.
     *
     * @param gameDir The Minecraft game directory
     * @return Detected version, or null if not found
     */
    public static String detectVersion(Path gameDir) {
        if (gameDir == null) {
            return null;
        }

        // Try CurseForge manifest.json in game directory
        String version = detectFromCurseForge(gameDir);
        if (version != null) {
            LOGGER.info("Detected version from manifest.json: " + version);
            return version;
        }

        // Try Modrinth pack.toml in game directory
        version = detectFromModrinth(gameDir);
        if (version != null) {
            LOGGER.info("Detected version from pack.toml: " + version);
            return version;
        }

        // Try parent directory (for instance-based launchers like MultiMC)
        Path parentDir = gameDir.getParent();
        if (parentDir != null) {
            version = detectFromCurseForge(parentDir);
            if (version != null) {
                LOGGER.info("Detected version from parent manifest.json: " + version);
                return version;
            }

            version = detectFromModrinth(parentDir);
            if (version != null) {
                LOGGER.info("Detected version from parent pack.toml: " + version);
                return version;
            }
        }

        LOGGER.fine("Could not detect modpack version");
        return null;
    }

    /**
     * Get the effective version, using config override if set.
     *
     * @param gameDir The Minecraft game directory
     * @param configOverride Version override from config (may be null or empty)
     * @return Effective version, or null if neither available
     */
    public static String getEffectiveVersion(Path gameDir, String configOverride) {
        if (configOverride != null && !configOverride.trim().isEmpty()) {
            LOGGER.info("Using version override from config: " + configOverride);
            return configOverride.trim();
        }
        return detectVersion(gameDir);
    }

    /**
     * Detect version from CurseForge manifest.json.
     * Expected format: {"version": "1.2.3", ...}
     */
    private static String detectFromCurseForge(Path dir) {
        Path manifest = dir.resolve("manifest.json");
        if (!Files.exists(manifest)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(manifest)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json != null && json.has("version")) {
                String version = json.get("version").getAsString();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse manifest.json: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Detect version from Modrinth pack.toml.
     * Expected format: version = "1.2.3"
     */
    private static String detectFromModrinth(Path dir) {
        Path packToml = dir.resolve("pack.toml");
        if (!Files.exists(packToml)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(packToml)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Look for: version = "1.2.3" or version = '1.2.3'
                if (line.startsWith("version")) {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        String value = line.substring(equals + 1).trim();
                        // Remove quotes (single or double)
                        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                            (value.startsWith("'") && value.endsWith("'"))) {
                            String version = value.substring(1, value.length() - 1);
                            if (!version.trim().isEmpty()) {
                                return version.trim();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse pack.toml: " + e.getMessage(), e);
        }
        return null;
    }
}

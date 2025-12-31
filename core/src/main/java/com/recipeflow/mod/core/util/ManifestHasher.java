package com.recipeflow.mod.core.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computes a deterministic hash from modpack manifests.
 * Supports CurseForge (manifest.json) and Modrinth (pack.toml).
 */
public class ManifestHasher {

    private static final Logger LOGGER = Logger.getLogger(ManifestHasher.class.getName());
    private static final Gson GSON = new Gson();

    private ManifestHasher() {
        // Utility class
    }

    /**
     * Compute manifest hash from the game directory.
     * Checks both game directory and parent directory for manifest files.
     *
     * @param gameDir The Minecraft game directory
     * @return HashResult with hash and metadata, or null if no manifest found
     */
    public static HashResult computeHash(Path gameDir) {
        if (gameDir == null) {
            return null;
        }

        // Try CurseForge manifest.json in game directory
        HashResult result = computeHashFromCurseForge(gameDir);
        if (result != null) {
            LOGGER.info("Computed hash from manifest.json: " + result.hash + " (" + result.modCount + " mods)");
            return result;
        }

        // Try Modrinth pack.toml in game directory
        result = computeHashFromModrinth(gameDir);
        if (result != null) {
            LOGGER.info("Computed hash from pack.toml: " + result.hash + " (" + result.modCount + " mods)");
            return result;
        }

        // Try parent directory (for instance-based launchers like MultiMC)
        Path parentDir = gameDir.getParent();
        if (parentDir != null) {
            result = computeHashFromCurseForge(parentDir);
            if (result != null) {
                LOGGER.info("Computed hash from parent manifest.json: " + result.hash + " (" + result.modCount + " mods)");
                return result;
            }

            result = computeHashFromModrinth(parentDir);
            if (result != null) {
                LOGGER.info("Computed hash from parent pack.toml: " + result.hash + " (" + result.modCount + " mods)");
                return result;
            }
        }

        LOGGER.fine("Could not compute manifest hash - no manifest file found");
        return null;
    }

    /**
     * Compute hash from CurseForge manifest.json.
     * Expected format: {"files": [{"projectID": 123, "fileID": 456}, ...]}
     */
    private static HashResult computeHashFromCurseForge(Path dir) {
        Path manifest = dir.resolve("manifest.json");
        if (!Files.exists(manifest)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(manifest)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null || !json.has("files")) {
                return null;
            }

            JsonArray files = json.getAsJsonArray("files");
            List<String> entries = new ArrayList<>();

            for (JsonElement elem : files) {
                if (!elem.isJsonObject()) {
                    continue;
                }
                JsonObject file = elem.getAsJsonObject();
                if (file.has("projectID") && file.has("fileID")) {
                    int projectId = file.get("projectID").getAsInt();
                    int fileId = file.get("fileID").getAsInt();
                    entries.add(projectId + ":" + fileId);
                }
            }

            if (entries.isEmpty()) {
                return null;
            }

            // Sort for deterministic hash
            Collections.sort(entries);

            // Compute SHA-256 hash
            String content = String.join("\n", entries);
            String hash = computeSha256(content);

            return new HashResult(hash, "curseforge", entries.size());

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse manifest.json: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Compute hash from Modrinth pack.toml.
     * Expected format in [[files]] sections with hash or path entries.
     */
    private static HashResult computeHashFromModrinth(Path dir) {
        Path packToml = dir.resolve("pack.toml");
        if (!Files.exists(packToml)) {
            return null;
        }

        try (BufferedReader reader = Files.newBufferedReader(packToml)) {
            String line;
            List<String> entries = new ArrayList<>();
            boolean inFilesSection = false;
            String currentHash = null;
            String currentPath = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Detect [[files]] section
                if (line.equals("[[files]]")) {
                    // Save previous entry if exists
                    if (currentHash != null || currentPath != null) {
                        String entry = (currentHash != null ? currentHash : currentPath);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                    inFilesSection = true;
                    currentHash = null;
                    currentPath = null;
                    continue;
                }

                // Detect new section (reset state)
                if (line.startsWith("[") && !line.startsWith("[[")) {
                    // Save last entry from files section
                    if (inFilesSection && (currentHash != null || currentPath != null)) {
                        String entry = (currentHash != null ? currentHash : currentPath);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                    inFilesSection = false;
                    currentHash = null;
                    currentPath = null;
                    continue;
                }

                // Parse key-value pairs in files section
                if (inFilesSection && line.contains("=")) {
                    int equals = line.indexOf('=');
                    String key = line.substring(0, equals).trim();
                    String value = line.substring(equals + 1).trim();

                    // Remove quotes
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }

                    if (key.equals("hash")) {
                        currentHash = value;
                    } else if (key.equals("path")) {
                        currentPath = value;
                    }
                }
            }

            // Save last entry
            if (inFilesSection && (currentHash != null || currentPath != null)) {
                String entry = (currentHash != null ? currentHash : currentPath);
                if (entry != null) {
                    entries.add(entry);
                }
            }

            if (entries.isEmpty()) {
                return null;
            }

            // Sort for deterministic hash
            Collections.sort(entries);

            // Compute SHA-256 hash
            String content = String.join("\n", entries);
            String hash = computeSha256(content);

            return new HashResult(hash, "modrinth", entries.size());

        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse pack.toml: " + e.getMessage(), e);
        }
        return null;
    }

    /**
     * Compute SHA-256 hash of content.
     *
     * @param content Content to hash
     * @return Hash string with "sha256:" prefix
     */
    private static String computeSha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex.toString();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to compute SHA-256 hash", e);
            return "sha256:unknown";
        }
    }

    /**
     * Result of manifest hash computation.
     */
    public static class HashResult {
        private final String hash;
        private final String format;
        private final int modCount;

        public HashResult(String hash, String format, int modCount) {
            this.hash = hash;
            this.format = format;
            this.modCount = modCount;
        }

        public String getHash() {
            return hash;
        }

        public String getFormat() {
            return format;
        }

        public int getModCount() {
            return modCount;
        }

        @Override
        public String toString() {
            return hash + " (" + format + ", " + modCount + " mods)";
        }
    }
}

package com.recipeflow.mod.v120plus.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.recipeflow.mod.core.auth.AuthToken;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * File-based storage for authentication tokens.
 * Stores tokens in config/recipeflow-auth.json.
 */
public class AuthStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthStorage.class);
    private static final String AUTH_FILE = "recipeflow-auth.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private AuthToken cachedToken = null;
    private long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 second cache

    /**
     * Load the stored token.
     * Returns null if no token is stored or if it's expired.
     */
    public AuthToken load() {
        // Check cache first
        if (cachedToken != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedToken.isExpired() ? null : cachedToken;
        }

        Path authFile = getAuthFilePath();
        if (!Files.exists(authFile)) {
            cachedToken = null;
            return null;
        }

        try {
            String json = new String(Files.readAllBytes(authFile), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = GSON.fromJson(json, Map.class);
            cachedToken = AuthToken.fromJsonMap(map);
            cacheTimestamp = System.currentTimeMillis();

            // Return null if token is expired
            if (cachedToken.isExpired()) {
                LOGGER.debug("Stored token has expired");
                return null;
            }

            return cachedToken;
        } catch (Exception e) {
            LOGGER.warn("Failed to load auth token", e);
            cachedToken = null;
            return null;
        }
    }

    /**
     * Save a token to storage.
     */
    public void save(AuthToken token) {
        Path authFile = getAuthFilePath();

        try {
            // Ensure parent directory exists
            Files.createDirectories(authFile.getParent());

            String json = GSON.toJson(token.toJsonMap());
            Files.write(authFile, json.getBytes(StandardCharsets.UTF_8));

            // Update cache
            cachedToken = token;
            cacheTimestamp = System.currentTimeMillis();

            LOGGER.info("Saved auth token to {}", authFile);
        } catch (IOException e) {
            LOGGER.error("Failed to save auth token", e);
        }
    }

    /**
     * Clear the stored token.
     */
    public void clear() {
        Path authFile = getAuthFilePath();

        try {
            if (Files.exists(authFile)) {
                Files.delete(authFile);
                LOGGER.info("Deleted auth token file");
            }
        } catch (IOException e) {
            LOGGER.error("Failed to delete auth token file", e);
        }

        cachedToken = null;
        cacheTimestamp = 0;
    }

    /**
     * Check if a token file exists.
     */
    public boolean exists() {
        return Files.exists(getAuthFilePath());
    }

    /**
     * Invalidate the cache, forcing a reload on next access.
     */
    public void invalidateCache() {
        cachedToken = null;
        cacheTimestamp = 0;
    }

    private Path getAuthFilePath() {
        return FMLPaths.CONFIGDIR.get().resolve(AUTH_FILE);
    }
}

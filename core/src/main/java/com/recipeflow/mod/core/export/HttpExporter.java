package com.recipeflow.mod.core.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.config.ModConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP client for sending recipe data to the RecipeFlow API.
 * Uses Java's HttpURLConnection for maximum compatibility (Java 8+).
 */
public class HttpExporter {

    private static final Logger LOGGER = Logger.getLogger(HttpExporter.class.getName());
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final String serverUrl;
    private final String authToken;
    private final String modpackSlug;
    private final int timeoutMs;
    private final boolean compressionEnabled;
    private final boolean debugLogging;

    public HttpExporter(ModConfig config) {
        this.serverUrl = normalizeUrl(config.getServerUrl());
        this.authToken = config.getAuthToken();
        this.modpackSlug = config.getModpackSlug();
        this.timeoutMs = config.getTimeoutMs();
        this.compressionEnabled = config.isCompressionEnabled();
        this.debugLogging = config.isDebugLogging();
    }

    /**
     * Sync recipes to the API (blocking).
     * POST /api/modpacks/{slug}/versions/{version}/recipes/sync
     *
     * @param recipes List of recipes to upload
     * @param modpackVersion The modpack version string
     * @param manifestHash Manifest hash for modpack verification (may be null)
     * @param callback Progress callback (may be null)
     * @return ExportResult with success/failure details
     */
    public ExportResult syncRecipes(List<RecipeData> recipes,
                                     String modpackVersion,
                                     String manifestHash,
                                     ProgressCallback callback) {
        try {
            // Build request body
            if (callback != null) {
                callback.onProgress(0, 100, "Preparing data...");
            }

            RecipeSerializer serializer = new RecipeSerializer();
            List<Map<String, Object>> recipeList = new ArrayList<>();
            for (RecipeData recipe : recipes) {
                recipeList.add(serializer.toRecordMap(recipe));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipeCount", recipes.size());
            if (manifestHash != null) {
                body.put("manifestHash", manifestHash);
            }
            body.put("recipes", recipeList);

            String jsonBody = GSON.toJson(body);
            String contentHash = computeHash(jsonBody);
            body.put("contentHash", contentHash);
            jsonBody = GSON.toJson(body);

            if (debugLogging) {
                LOGGER.info("Request body size: " + jsonBody.length() + " bytes");
            }

            // Build URL
            String url = String.format("%s/api/modpacks/%s/versions/%s/recipes/sync",
                    serverUrl,
                    urlEncode(modpackSlug),
                    urlEncode(modpackVersion));

            if (debugLogging) {
                LOGGER.info("Syncing to: " + url);
            }

            if (callback != null) {
                callback.onProgress(10, 100, "Connecting to server...");
            }

            // Create connection
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            // Headers
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

            // Prepare body (optionally compress)
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            if (compressionEnabled) {
                conn.setRequestProperty("Content-Encoding", "gzip");
                bodyBytes = gzipCompress(bodyBytes);
                if (debugLogging) {
                    LOGGER.info("Compressed size: " + bodyBytes.length + " bytes");
                }
            }

            if (callback != null) {
                callback.onProgress(20, 100, "Uploading " + recipes.size() + " recipes...");
            }

            // Write body
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bodyBytes);
                out.flush();
            }

            if (callback != null) {
                callback.onProgress(80, 100, "Waiting for response...");
            }

            // Read response
            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            if (debugLogging) {
                LOGGER.info("Response code: " + responseCode);
                LOGGER.info("Response body: " + responseBody);
            }

            if (callback != null) {
                callback.onProgress(100, 100, "Processing response...");
            }

            if (responseCode >= 200 && responseCode < 300) {
                return parseSuccessResponse(responseBody, recipes.size());
            } else {
                String errorMsg = "Server returned " + responseCode;
                if (responseBody != null && !responseBody.isEmpty()) {
                    // Try to extract error message from JSON response
                    try {
                        JsonObject errorJson = GSON.fromJson(responseBody, JsonObject.class);
                        if (errorJson.has("error")) {
                            errorMsg += ": " + errorJson.get("error").getAsString();
                        } else if (errorJson.has("message")) {
                            errorMsg += ": " + errorJson.get("message").getAsString();
                        } else {
                            errorMsg += ": " + responseBody;
                        }
                    } catch (Exception e) {
                        errorMsg += ": " + responseBody;
                    }
                }
                return ExportResult.error(errorMsg);
            }

        } catch (java.net.SocketTimeoutException e) {
            return ExportResult.error("Connection timed out. Check your network and server URL.", e);
        } catch (java.net.UnknownHostException e) {
            return ExportResult.error("Could not resolve server hostname. Check the server URL.", e);
        } catch (java.net.ConnectException e) {
            return ExportResult.error("Could not connect to server. Is it running?", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Sync failed", e);
            return ExportResult.error("Network error: " + e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload without manifestHash.
     *
     * @deprecated Use {@link #syncRecipes(List, String, String, ProgressCallback)} instead
     */
    public ExportResult syncRecipes(List<RecipeData> recipes,
                                     String modpackVersion,
                                     ProgressCallback callback) {
        return syncRecipes(recipes, modpackVersion, null, callback);
    }

    /**
     * Async version for non-blocking operation.
     */
    public CompletableFuture<ExportResult> syncRecipesAsync(
            List<RecipeData> recipes,
            String modpackVersion,
            String manifestHash,
            ProgressCallback callback) {
        return CompletableFuture.supplyAsync(() ->
                syncRecipes(recipes, modpackVersion, manifestHash, callback)
        );
    }

    /**
     * Async version for non-blocking operation (backward-compatible overload).
     *
     * @deprecated Use {@link #syncRecipesAsync(List, String, String, ProgressCallback)} instead
     */
    public CompletableFuture<ExportResult> syncRecipesAsync(
            List<RecipeData> recipes,
            String modpackVersion,
            ProgressCallback callback) {
        return syncRecipesAsync(recipes, modpackVersion, null, callback);
    }

    // === Helper Methods ===

    private byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    private String computeHash(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "sha256:" + hex.toString();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to compute hash", e);
            return "unknown";
        }
    }

    private String readResponse(HttpURLConnection conn) throws IOException {
        InputStream stream;
        try {
            stream = conn.getInputStream();
        } catch (IOException e) {
            stream = conn.getErrorStream();
        }

        if (stream == null) {
            return "";
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private ExportResult parseSuccessResponse(String json, int recipesUploaded) {
        if (json == null || json.isEmpty()) {
            return ExportResult.success(recipesUploaded);
        }

        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            // Check for explicit success flag
            if (obj.has("success") && !obj.get("success").getAsBoolean()) {
                String error = obj.has("error") ? obj.get("error").getAsString() : "Unknown error";
                return ExportResult.error(error);
            }

            // Parse stats if available
            if (obj.has("stats")) {
                JsonObject stats = obj.getAsJsonObject("stats");
                return ExportResult.success(
                        stats.has("received") ? stats.get("received").getAsInt() : recipesUploaded,
                        stats.has("new") ? stats.get("new").getAsInt() : 0,
                        stats.has("updated") ? stats.get("updated").getAsInt() : 0,
                        stats.has("unchanged") ? stats.get("unchanged").getAsInt() : 0,
                        obj.has("contentHash") ? obj.get("contentHash").getAsString() : null,
                        obj.has("version") ? obj.get("version").getAsString() : null
                );
            }

            return ExportResult.success(recipesUploaded);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Failed to parse response", e);
            // Response parsing failed but the request succeeded
            return ExportResult.success(recipesUploaded);
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        url = url.trim();
        // Remove trailing slash
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            // Should never happen with UTF-8
            return value;
        }
    }

    /**
     * Callback for progress reporting.
     */
    public interface ProgressCallback {
        /**
         * Called to report progress.
         *
         * @param current Current progress value
         * @param total Maximum progress value (typically 100)
         * @param message Human-readable status message
         */
        void onProgress(int current, int total, String message);
    }
}

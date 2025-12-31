package com.recipeflow.mod.core.upload;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * Chunked upload client for large payload uploads.
 * Implements resumable upload protocol with per-chunk hash verification.
 *
 * <p>Protocol flow:
 * <ol>
 *   <li>Start session: POST /api/modpacks/{slug}/versions/{version}/upload/start</li>
 *   <li>Get upload status: GET /api/.../upload/{sessionId}/status</li>
 *   <li>Upload chunks: POST /api/.../upload/{sessionId}/chunk/{index}</li>
 *   <li>Complete upload: POST /api/.../upload/{sessionId}/complete</li>
 * </ol>
 */
public class ChunkedUploader {

    private static final Logger LOGGER = Logger.getLogger(ChunkedUploader.class.getName());
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    // Default chunk size: 1MB
    private static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;

    private final String serverUrl;
    private final String authToken;
    private final String modpackSlug;
    private final int timeoutMs;
    private final boolean compressionEnabled;
    private final boolean debugLogging;
    private final int chunkSize;

    /**
     * Create a new chunked uploader with default chunk size (1MB).
     *
     * @param config Mod configuration
     */
    public ChunkedUploader(ModConfig config) {
        this(config, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Create a new chunked uploader with custom chunk size.
     *
     * @param config Mod configuration
     * @param chunkSize Size of each chunk in bytes
     */
    public ChunkedUploader(ModConfig config, int chunkSize) {
        this.serverUrl = normalizeUrl(config.getServerUrl());
        this.authToken = config.getAuthToken();
        this.modpackSlug = config.getModpackSlug();
        this.timeoutMs = config.getTimeoutMs();
        this.compressionEnabled = config.isCompressionEnabled();
        this.debugLogging = config.isDebugLogging();
        this.chunkSize = chunkSize;
    }

    /**
     * Check if an upload of the given type already exists for this version.
     * This can be used to skip expensive export operations if content already exists.
     *
     * @param modpackVersion Modpack version string
     * @param uploadType Type of upload (icons, items, recipes)
     * @return true if content already exists on server, false otherwise
     */
    public boolean checkUploadExists(String modpackVersion, String uploadType) {
        try {
            String url = String.format("%s/api/modpacks/%s/versions/%s/upload/check?type=%s",
                    serverUrl,
                    urlEncode(modpackSlug),
                    urlEncode(modpackVersion),
                    urlEncode(uploadType));

            if (debugLogging) {
                LOGGER.info("Checking if upload exists at: " + url);
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Authorization", "Bearer " + authToken);
            conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            if (debugLogging) {
                LOGGER.info("Check response: " + responseCode + " - " + responseBody);
            }

            if (responseCode >= 200 && responseCode < 300) {
                JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
                return json.has("exists") && json.get("exists").getAsBoolean();
            }

            // If endpoint doesn't exist (404) or other error, assume content doesn't exist
            return false;

        } catch (Exception e) {
            if (debugLogging) {
                LOGGER.log(Level.WARNING, "Failed to check upload existence", e);
            }
            // On error, assume content doesn't exist (safer to re-upload than skip)
            return false;
        }
    }

    /**
     * Upload data using chunked protocol with resume support.
     *
     * @param data Data to upload
     * @param modpackVersion Modpack version string
     * @param uploadType Type of upload (icons, items, recipes)
     * @param callback Progress callback (may be null)
     * @return UploadResult with success/failure details
     */
    public UploadResult upload(byte[] data, String modpackVersion, String uploadType,
                               ProgressCallback callback) {
        String sessionId = null;
        int chunksUploaded = 0;
        long bytesUploaded = 0;

        try {
            // Step 1: Start upload session
            if (callback != null) {
                callback.onProgress(0, 100, "Starting upload session...");
            }

            String finalHash = computeHash(data);
            sessionId = startSession(modpackVersion, uploadType, data.length, finalHash);
            if (debugLogging) {
                LOGGER.info("Started upload session: " + sessionId);
            }

            // Step 2: Get already uploaded chunks (for resume support)
            if (callback != null) {
                callback.onProgress(5, 100, "Checking upload status...");
            }

            List<Integer> uploadedChunks = getUploadedChunks(modpackVersion, sessionId);
            int totalChunks = (int) Math.ceil((double) data.length / chunkSize);

            if (debugLogging) {
                LOGGER.info(String.format("Total chunks: %d, Already uploaded: %d",
                        totalChunks, uploadedChunks.size()));
            }

            // Step 3: Upload chunks
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                // Skip already uploaded chunks
                if (uploadedChunks.contains(chunkIndex)) {
                    if (debugLogging) {
                        LOGGER.info("Skipping already uploaded chunk " + chunkIndex);
                    }
                    chunksUploaded++;
                    bytesUploaded += getChunkSize(data.length, chunkIndex);
                    continue;
                }

                // Calculate progress
                int progress = 5 + (int) ((90.0 * (chunkIndex + 1)) / totalChunks);
                if (callback != null) {
                    callback.onProgress(progress, 100,
                            String.format("Uploading chunk %d/%d...", chunkIndex + 1, totalChunks));
                }

                // Extract chunk data
                int startPos = chunkIndex * chunkSize;
                int endPos = Math.min(startPos + chunkSize, data.length);
                byte[] chunkData = Arrays.copyOfRange(data, startPos, endPos);

                // Compute chunk hash
                String chunkHash = computeHash(chunkData);

                // Upload chunk
                uploadChunk(modpackVersion, sessionId, chunkIndex, chunkData, chunkHash);
                chunksUploaded++;
                bytesUploaded += chunkData.length;

                if (debugLogging) {
                    LOGGER.info(String.format("Uploaded chunk %d/%d (%d bytes, hash: %s)",
                            chunkIndex + 1, totalChunks, chunkData.length, chunkHash));
                }
            }

            // Step 4: Complete upload
            if (callback != null) {
                callback.onProgress(95, 100, "Completing upload...");
            }

            String contentHash = computeHash(data);
            completeUpload(modpackVersion, sessionId, contentHash);

            if (debugLogging) {
                LOGGER.info("Upload completed successfully");
            }

            if (callback != null) {
                callback.onProgress(100, 100, "Upload complete!");
            }

            return UploadResult.success(sessionId, chunksUploaded, bytesUploaded);

        } catch (java.net.SocketTimeoutException e) {
            return UploadResult.error("Connection timed out. Check your network and server URL.", e);
        } catch (java.net.UnknownHostException e) {
            return UploadResult.error("Could not resolve server hostname. Check the server URL.", e);
        } catch (java.net.ConnectException e) {
            return UploadResult.error("Could not connect to server. Is it running?", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Upload failed", e);
            return UploadResult.error("Upload error: " + e.getMessage(), e);
        }
    }

    // === Protocol Implementation ===

    /**
     * Step 1: Start a new upload session.
     * POST /api/modpacks/{slug}/versions/{version}/upload/start
     */
    private String startSession(String version, String type, int totalSize, String finalHash) throws Exception {
        String url = String.format("%s/api/modpacks/%s/versions/%s/upload/start",
                serverUrl,
                urlEncode(modpackSlug),
                urlEncode(version));

        // Build request body
        int totalChunks = (int) Math.ceil((double) totalSize / chunkSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("totalSize", totalSize);
        body.put("chunkSize", chunkSize);
        body.put("totalChunks", totalChunks);
        body.put("finalHash", finalHash);

        String jsonBody = GSON.toJson(body);

        if (debugLogging) {
            LOGGER.info("Starting session at: " + url);
            LOGGER.info("Request body: " + jsonBody);
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

        // Write body
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
            out.flush();
        }

        // Read response
        int responseCode = conn.getResponseCode();
        String responseBody = readResponse(conn);

        if (debugLogging) {
            LOGGER.info("Response code: " + responseCode);
            LOGGER.info("Response body: " + responseBody);
        }

        if (responseCode >= 200 && responseCode < 300) {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            return json.get("sessionId").getAsString();
        } else {
            throw new IOException("Failed to start session: " + responseCode + " - " + responseBody);
        }
    }

    /**
     * Step 2: Get list of already uploaded chunks.
     * GET /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/status
     */
    private List<Integer> getUploadedChunks(String version, String sessionId) throws Exception {
        String url = String.format("%s/api/modpacks/%s/versions/%s/upload/%s/status",
                serverUrl,
                urlEncode(modpackSlug),
                urlEncode(version),
                urlEncode(sessionId));

        if (debugLogging) {
            LOGGER.info("Getting upload status from: " + url);
        }

        // Create connection
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

        // Headers
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

        // Read response
        int responseCode = conn.getResponseCode();
        String responseBody = readResponse(conn);

        if (debugLogging) {
            LOGGER.info("Response code: " + responseCode);
            LOGGER.info("Response body: " + responseBody);
        }

        if (responseCode >= 200 && responseCode < 300) {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            List<Integer> uploadedChunks = new ArrayList<>();

            // API returns "chunksReceived" array with indices of uploaded chunks
            if (json.has("chunksReceived") && !json.get("chunksReceived").isJsonNull()) {
                JsonArray uploadedArray = json.getAsJsonArray("chunksReceived");
                for (int i = 0; i < uploadedArray.size(); i++) {
                    uploadedChunks.add(uploadedArray.get(i).getAsInt());
                }
            }
            return uploadedChunks;
        } else {
            throw new IOException("Failed to get upload status: " + responseCode + " - " + responseBody);
        }
    }

    /**
     * Step 3: Upload a single chunk.
     * POST /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/chunk/{index}
     */
    private void uploadChunk(String version, String sessionId, int chunkIndex,
                            byte[] chunkData, String chunkHash) throws Exception {
        String url = String.format("%s/api/modpacks/%s/versions/%s/upload/%s/chunk/%d",
                serverUrl,
                urlEncode(modpackSlug),
                urlEncode(version),
                urlEncode(sessionId),
                chunkIndex);

        if (debugLogging) {
            LOGGER.info("Uploading chunk to: " + url);
        }

        // Create connection
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

        // Headers
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("X-Chunk-Hash", chunkHash);
        conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

        // Optionally compress chunk
        byte[] bodyBytes = chunkData;
        if (compressionEnabled) {
            conn.setRequestProperty("Content-Encoding", "gzip");
            bodyBytes = gzipCompress(chunkData);
            if (debugLogging) {
                LOGGER.info(String.format("Compressed chunk from %d to %d bytes",
                        chunkData.length, bodyBytes.length));
            }
        }

        // Write body
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
            out.flush();
        }

        // Read response
        int responseCode = conn.getResponseCode();
        String responseBody = readResponse(conn);

        if (debugLogging) {
            LOGGER.info("Response code: " + responseCode);
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Failed to upload chunk " + chunkIndex + ": " + responseCode + " - " + responseBody);
        }
    }

    /**
     * Step 4: Complete the upload.
     * POST /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/complete
     *
     * Note: No request body needed - the finalHash was provided at session start.
     */
    private void completeUpload(String version, String sessionId, String contentHash) throws Exception {
        String url = String.format("%s/api/modpacks/%s/versions/%s/upload/%s/complete",
                serverUrl,
                urlEncode(modpackSlug),
                urlEncode(version),
                urlEncode(sessionId));

        if (debugLogging) {
            LOGGER.info("Completing upload at: " + url);
        }

        // Create connection
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

        // Headers
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

        // Read response
        int responseCode = conn.getResponseCode();
        String responseBody = readResponse(conn);

        if (debugLogging) {
            LOGGER.info("Response code: " + responseCode);
            LOGGER.info("Response body: " + responseBody);
        }

        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("Failed to complete upload: " + responseCode + " - " + responseBody);
        }
    }

    // === Helper Methods ===

    /**
     * Compute SHA-256 hash of byte array.
     */
    private String computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
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

    /**
     * GZIP compress byte array.
     */
    private byte[] gzipCompress(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Read response from HTTP connection.
     */
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

    /**
     * Normalize URL by removing trailing slashes.
     */
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

    /**
     * URL encode a string value.
     */
    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            // Should never happen with UTF-8
            return value;
        }
    }

    /**
     * Calculate the size of a specific chunk.
     */
    private int getChunkSize(int totalSize, int chunkIndex) {
        int startPos = chunkIndex * chunkSize;
        int endPos = Math.min(startPos + chunkSize, totalSize);
        return endPos - startPos;
    }

    /**
     * Callback for progress reporting during upload.
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

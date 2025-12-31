package com.recipeflow.mod.core.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for OAuth 2.0 Device Authorization Grant (RFC 8628).
 * Handles requesting device codes and polling for tokens.
 */
public class DeviceFlowClient {

    private static final Logger LOGGER = Logger.getLogger(DeviceFlowClient.class.getName());
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static final String CLIENT_ID = "recipeflow-mod";

    private final String serverUrl;
    private final int timeoutMs;
    private final boolean debugLogging;

    public DeviceFlowClient(String serverUrl, int timeoutMs, boolean debugLogging) {
        this.serverUrl = normalizeUrl(serverUrl);
        this.timeoutMs = timeoutMs;
        this.debugLogging = debugLogging;
    }

    /**
     * Request a new device code from the API.
     * POST /api/auth/device/code
     *
     * @return DeviceCodeResponse with device code, user code, and verification URL
     * @throws IOException if the request fails
     */
    public DeviceCodeResponse requestDeviceCode() throws IOException {
        String url = serverUrl + "/api/auth/device/code";

        if (debugLogging) {
            LOGGER.info("Requesting device code from: " + url);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

        // Request body - API expects snake_case field names
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", CLIENT_ID);
        String jsonBody = GSON.toJson(body);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        int responseCode = conn.getResponseCode();
        String responseBody = readResponse(conn);

        if (debugLogging) {
            LOGGER.info("Device code response: " + responseCode + " - " + responseBody);
        }

        if (responseCode >= 200 && responseCode < 300) {
            return parseDeviceCodeResponse(responseBody);
        } else {
            throw new IOException("Failed to request device code: " + responseCode + " - " + responseBody);
        }
    }

    /**
     * Poll for token completion.
     * POST /api/auth/device/token
     *
     * @param deviceCode The device code from requestDeviceCode()
     * @return AuthResult with token on success, or pending/error status
     */
    public AuthResult pollForToken(String deviceCode) {
        try {
            String url = serverUrl + "/api/auth/device/token";

            if (debugLogging) {
                LOGGER.info("Polling for token: " + url);
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "RecipeFlow-Mod/1.0");

            // Request body - OAuth 2.0 Device Authorization Grant format (RFC 8628)
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            body.put("client_id", CLIENT_ID);
            body.put("device_code", deviceCode);
            String jsonBody = GSON.toJson(body);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn);

            if (debugLogging) {
                LOGGER.info("Token poll response: " + responseCode + " - " + responseBody);
            }

            return parseTokenResponse(responseCode, responseBody);

        } catch (java.net.SocketTimeoutException e) {
            return AuthResult.error("Connection timed out", e);
        } catch (java.net.UnknownHostException e) {
            return AuthResult.error("Could not resolve server hostname", e);
        } catch (java.net.ConnectException e) {
            return AuthResult.error("Could not connect to server", e);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Token poll failed", e);
            return AuthResult.error("Network error: " + e.getMessage(), e);
        }
    }

    // === Response Parsing ===

    private DeviceCodeResponse parseDeviceCodeResponse(String json) throws IOException {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            // API returns snake_case field names per OAuth 2.0 spec
            String deviceCode = obj.get("device_code").getAsString();
            String userCode = obj.get("user_code").getAsString();
            String verificationUri = obj.get("verification_uri").getAsString();

            String verificationUriComplete = null;
            if (obj.has("verification_uri_complete") && !obj.get("verification_uri_complete").isJsonNull()) {
                verificationUriComplete = obj.get("verification_uri_complete").getAsString();
            }

            int expiresIn = obj.has("expires_in") ? obj.get("expires_in").getAsInt() : 900;
            int interval = obj.has("interval") ? obj.get("interval").getAsInt() : 5;

            return new DeviceCodeResponse(
                    deviceCode,
                    userCode,
                    verificationUri,
                    verificationUriComplete,
                    expiresIn,
                    interval
            );
        } catch (Exception e) {
            throw new IOException("Failed to parse device code response: " + json, e);
        }
    }

    private AuthResult parseTokenResponse(int responseCode, String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);

            // Check for error response (400)
            if (obj.has("error")) {
                String errorCode = obj.get("error").getAsString();
                String errorDescription = obj.has("error_description")
                        ? obj.get("error_description").getAsString()
                        : errorCode;
                return AuthResult.fromApiError(errorCode, errorDescription);
            }

            // Success response (200) - support both camelCase and snake_case field names
            if (responseCode >= 200 && responseCode < 300) {
                String accessToken = null;
                if (obj.has("accessToken")) {
                    accessToken = obj.get("accessToken").getAsString();
                } else if (obj.has("access_token")) {
                    accessToken = obj.get("access_token").getAsString();
                }

                if (accessToken != null) {
                    String tokenType = "Bearer";
                    if (obj.has("tokenType")) {
                        tokenType = obj.get("tokenType").getAsString();
                    } else if (obj.has("token_type")) {
                        tokenType = obj.get("token_type").getAsString();
                    }

                    int expiresIn = 604800;
                    if (obj.has("expiresIn")) {
                        expiresIn = obj.get("expiresIn").getAsInt();
                    } else if (obj.has("expires_in")) {
                        expiresIn = obj.get("expires_in").getAsInt();
                    }

                    AuthToken token = AuthToken.fromResponse(accessToken, tokenType, expiresIn);
                    return AuthResult.success(token);
                }
            }

            // Unknown response
            return AuthResult.error("unexpected_response", "Unexpected response: " + json);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse token response", e);
            return AuthResult.error("Failed to parse response: " + e.getMessage(), e);
        }
    }

    // === Helper Methods ===

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

    private String normalizeUrl(String url) {
        if (url == null) {
            return "";
        }
        url = url.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // === Device Code Response ===

    /**
     * Response from the device code request.
     */
    public static class DeviceCodeResponse {
        private final String deviceCode;
        private final String userCode;
        private final String verificationUri;
        private final String verificationUriComplete;
        private final int expiresIn;
        private final int interval;

        public DeviceCodeResponse(String deviceCode, String userCode, String verificationUri,
                                  String verificationUriComplete, int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.verificationUriComplete = verificationUriComplete;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public String getUserCode() {
            return userCode;
        }

        public String getVerificationUri() {
            return verificationUri;
        }

        /**
         * Get the verification URL with code pre-filled.
         * Falls back to base verification URI if not provided.
         */
        public String getVerificationUriComplete() {
            return verificationUriComplete != null ? verificationUriComplete : verificationUri;
        }

        public int getExpiresIn() {
            return expiresIn;
        }

        public int getInterval() {
            return interval;
        }

        @Override
        public String toString() {
            return "DeviceCodeResponse{" +
                    "userCode='" + userCode + '\'' +
                    ", verificationUri='" + verificationUri + '\'' +
                    ", expiresIn=" + expiresIn +
                    ", interval=" + interval +
                    '}';
        }
    }
}

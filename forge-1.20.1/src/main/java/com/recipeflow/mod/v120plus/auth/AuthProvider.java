package com.recipeflow.mod.v120plus.auth;

import com.recipeflow.mod.core.auth.AuthToken;
import com.recipeflow.mod.v120plus.config.ForgeConfig120;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified authentication token provider.
 * Prioritizes device flow token over config file token.
 */
public class AuthProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthProvider.class);

    public static final AuthProvider INSTANCE = new AuthProvider();

    private final AuthStorage storage = new AuthStorage();

    private AuthProvider() {
        // Singleton
    }

    /**
     * Get the current authentication token.
     * Checks device flow storage first, falls back to config file.
     *
     * @return The auth token string, or empty string if not authenticated
     */
    public String getAuthToken() {
        // Check device flow token first
        AuthToken token = storage.load();
        if (token != null && !token.isExpired()) {
            return token.getAccessToken();
        }

        // Fall back to config file token
        String configToken = getConfigToken();
        if (configToken != null && !configToken.trim().isEmpty()) {
            return configToken;
        }

        return "";
    }

    /**
     * Check if the user is authenticated via any method.
     */
    public boolean isAuthenticated() {
        String token = getAuthToken();
        return token != null && !token.trim().isEmpty();
    }

    /**
     * Check if authenticated via device flow (vs config file).
     */
    public boolean isDeviceFlowAuthenticated() {
        AuthToken token = storage.load();
        return token != null && !token.isExpired();
    }

    /**
     * Get the source of the current authentication.
     *
     * @return "device flow", "config file", or "not authenticated"
     */
    public String getAuthSource() {
        AuthToken deviceToken = storage.load();
        if (deviceToken != null && !deviceToken.isExpired()) {
            return "device flow";
        }

        String configToken = getConfigToken();
        if (configToken != null && !configToken.trim().isEmpty()) {
            return "config file";
        }

        return "not authenticated";
    }

    /**
     * Get the stored device flow token (may be null or expired).
     */
    public AuthToken getDeviceFlowToken() {
        return storage.load();
    }

    /**
     * Save a new device flow token.
     */
    public void saveToken(AuthToken token) {
        storage.save(token);
        LOGGER.info("Device flow token saved");
    }

    /**
     * Clear the device flow token (logout).
     */
    public void clearToken() {
        storage.clear();
        LOGGER.info("Device flow token cleared");
    }

    /**
     * Check if a device flow token file exists.
     */
    public boolean hasStoredToken() {
        return storage.exists();
    }

    /**
     * Get token expiration status.
     *
     * @return Human-readable expiration info, or null if no device flow token
     */
    public String getTokenExpirationInfo() {
        AuthToken token = storage.load();
        if (token == null) {
            return null;
        }

        if (token.isExpired()) {
            return "expired";
        }

        if (token.getExpiresAt() != null) {
            long remainingMs = token.getExpiresAt().toEpochMilli() - System.currentTimeMillis();
            long remainingHours = remainingMs / (1000 * 60 * 60);
            long remainingDays = remainingHours / 24;

            if (remainingDays > 1) {
                return "expires in " + remainingDays + " days";
            } else if (remainingHours > 1) {
                return "expires in " + remainingHours + " hours";
            } else {
                return "expires soon";
            }
        }

        return "valid";
    }

    /**
     * Get the config file token directly.
     */
    private String getConfigToken() {
        try {
            // Access config directly to avoid infinite recursion
            return ForgeConfig120.INSTANCE.getConfigAuthToken();
        } catch (Exception e) {
            LOGGER.debug("Failed to get config token", e);
            return null;
        }
    }
}

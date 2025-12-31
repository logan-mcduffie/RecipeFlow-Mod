package com.recipeflow.mod.core.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents an authentication token received from the device flow.
 * Immutable data class with JSON serialization support.
 */
public class AuthToken {

    private final String accessToken;
    private final String tokenType;
    private final Instant expiresAt;
    private final Instant authenticatedAt;

    public AuthToken(String accessToken, String tokenType, Instant expiresAt, Instant authenticatedAt) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresAt = expiresAt;
        this.authenticatedAt = authenticatedAt;
    }

    /**
     * Create a token from an API response.
     *
     * @param accessToken The access token string
     * @param tokenType   Token type (usually "Bearer")
     * @param expiresIn   Seconds until expiration
     */
    public static AuthToken fromResponse(String accessToken, String tokenType, int expiresIn) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expiresIn);
        return new AuthToken(accessToken, tokenType, expiresAt, now);
    }

    // === Getters ===

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAuthenticatedAt() {
        return authenticatedAt;
    }

    // === Status Checks ===

    /**
     * Check if the token has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Check if the token will expire within the given number of minutes.
     * Useful for proactive refresh.
     */
    public boolean expiresWithin(int minutes) {
        if (expiresAt == null) {
            return false;
        }
        return Instant.now().plusSeconds(minutes * 60L).isAfter(expiresAt);
    }

    // === JSON Serialization ===

    /**
     * Convert to a map for JSON serialization.
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accessToken", accessToken);
        map.put("tokenType", tokenType);
        if (expiresAt != null) {
            map.put("expiresAt", expiresAt.toString());
        }
        if (authenticatedAt != null) {
            map.put("authenticatedAt", authenticatedAt.toString());
        }
        return map;
    }

    /**
     * Create from a JSON map (deserialization).
     */
    public static AuthToken fromJsonMap(Map<String, Object> map) {
        String accessToken = (String) map.get("accessToken");
        String tokenType = (String) map.get("tokenType");

        Instant expiresAt = null;
        Object expiresAtObj = map.get("expiresAt");
        if (expiresAtObj instanceof String) {
            expiresAt = Instant.parse((String) expiresAtObj);
        }

        Instant authenticatedAt = null;
        Object authAtObj = map.get("authenticatedAt");
        if (authAtObj instanceof String) {
            authenticatedAt = Instant.parse((String) authAtObj);
        }

        return new AuthToken(accessToken, tokenType, expiresAt, authenticatedAt);
    }

    @Override
    public String toString() {
        return "AuthToken{" +
                "tokenType='" + tokenType + '\'' +
                ", expiresAt=" + expiresAt +
                ", authenticatedAt=" + authenticatedAt +
                ", accessToken='" + (accessToken != null ? "[REDACTED]" : "null") + '\'' +
                '}';
    }
}

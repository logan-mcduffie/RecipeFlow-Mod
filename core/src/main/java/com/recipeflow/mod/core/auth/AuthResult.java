package com.recipeflow.mod.core.auth;

/**
 * Result of an authentication operation.
 * Immutable result object following the ExportResult pattern.
 */
public class AuthResult {

    private final Status status;
    private final AuthToken token;
    private final String errorCode;
    private final String errorMessage;
    private final Throwable exception;

    public enum Status {
        SUCCESS,
        PENDING,
        SLOW_DOWN,
        EXPIRED,
        DENIED,
        ERROR
    }

    private AuthResult(Status status, AuthToken token, String errorCode,
                       String errorMessage, Throwable exception) {
        this.status = status;
        this.token = token;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.exception = exception;
    }

    // === Factory Methods ===

    /**
     * Create a successful result with the received token.
     */
    public static AuthResult success(AuthToken token) {
        return new AuthResult(Status.SUCCESS, token, null, null, null);
    }

    /**
     * Create a pending result (user hasn't completed authorization yet).
     */
    public static AuthResult pending() {
        return new AuthResult(Status.PENDING, null, "authorization_pending",
                "Waiting for user authorization", null);
    }

    /**
     * Create a slow down result (polling too frequently).
     */
    public static AuthResult slowDown() {
        return new AuthResult(Status.SLOW_DOWN, null, "slow_down",
                "Polling too frequently", null);
    }

    /**
     * Create an expired result (device code expired).
     */
    public static AuthResult expired() {
        return new AuthResult(Status.EXPIRED, null, "expired_token",
                "Device code has expired", null);
    }

    /**
     * Create a denied result (user denied authorization).
     */
    public static AuthResult denied() {
        return new AuthResult(Status.DENIED, null, "access_denied",
                "Authorization was denied", null);
    }

    /**
     * Create an error result with code and message.
     */
    public static AuthResult error(String errorCode, String message) {
        return new AuthResult(Status.ERROR, null, errorCode, message, null);
    }

    /**
     * Create an error result with message and exception.
     */
    public static AuthResult error(String message, Throwable exception) {
        return new AuthResult(Status.ERROR, null, null, message, exception);
    }

    /**
     * Create an error result from an API error response.
     */
    public static AuthResult fromApiError(String errorCode, String description) {
        switch (errorCode) {
            case "authorization_pending":
                return pending();
            case "slow_down":
                return slowDown();
            case "expired_token":
                return expired();
            case "access_denied":
                return denied();
            default:
                return error(errorCode, description);
        }
    }

    // === Getters ===

    public Status getStatus() {
        return status;
    }

    public AuthToken getToken() {
        return token;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Throwable getException() {
        return exception;
    }

    // === Status Checks ===

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isSlowDown() {
        return status == Status.SLOW_DOWN;
    }

    public boolean isExpired() {
        return status == Status.EXPIRED;
    }

    public boolean isDenied() {
        return status == Status.DENIED;
    }

    public boolean isError() {
        return status == Status.ERROR;
    }

    /**
     * Check if polling should continue.
     * Returns true for PENDING and SLOW_DOWN states.
     */
    public boolean shouldContinuePolling() {
        return status == Status.PENDING || status == Status.SLOW_DOWN;
    }

    // === Formatted Summary ===

    /**
     * Get a human-readable summary of the result.
     */
    public String getSummary() {
        switch (status) {
            case SUCCESS:
                return "Successfully authenticated!";
            case PENDING:
                return "Waiting for authorization...";
            case SLOW_DOWN:
                return "Polling too fast, slowing down...";
            case EXPIRED:
                return "Login expired. Please try again.";
            case DENIED:
                return "Authorization was denied.";
            case ERROR:
            default:
                return "Authentication failed: " +
                        (errorMessage != null ? errorMessage : "Unknown error");
        }
    }

    @Override
    public String toString() {
        return "AuthResult{" +
                "status=" + status +
                ", errorCode='" + errorCode + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                ", hasToken=" + (token != null) +
                '}';
    }
}

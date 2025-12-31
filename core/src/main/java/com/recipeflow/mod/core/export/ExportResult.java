package com.recipeflow.mod.core.export;

/**
 * Result of a recipe sync/export operation.
 * Matches the API response structure.
 */
public class ExportResult {

    private final boolean success;
    private final int recipesReceived;
    private final int recipesNew;
    private final int recipesUpdated;
    private final int recipesUnchanged;
    private final String contentHash;
    private final String version;
    private final String errorMessage;
    private final Throwable exception;

    private ExportResult(boolean success, int recipesReceived, int recipesNew,
                         int recipesUpdated, int recipesUnchanged,
                         String contentHash, String version,
                         String errorMessage, Throwable exception) {
        this.success = success;
        this.recipesReceived = recipesReceived;
        this.recipesNew = recipesNew;
        this.recipesUpdated = recipesUpdated;
        this.recipesUnchanged = recipesUnchanged;
        this.contentHash = contentHash;
        this.version = version;
        this.errorMessage = errorMessage;
        this.exception = exception;
    }

    // === Factory Methods ===

    /**
     * Create a successful result with statistics from the server response.
     */
    public static ExportResult success(int received, int newCount,
                                        int updated, int unchanged,
                                        String contentHash, String version) {
        return new ExportResult(true, received, newCount, updated, unchanged,
                contentHash, version, null, null);
    }

    /**
     * Create a simple success result (for when server doesn't return stats).
     */
    public static ExportResult success(int recipesUploaded) {
        return new ExportResult(true, recipesUploaded, 0, 0, 0,
                null, null, null, null);
    }

    /**
     * Create an error result with message and exception.
     */
    public static ExportResult error(String message, Throwable exception) {
        return new ExportResult(false, 0, 0, 0, 0,
                null, null, message, exception);
    }

    /**
     * Create an error result with message only.
     */
    public static ExportResult error(String message) {
        return error(message, null);
    }

    // === Getters ===

    public boolean isSuccess() {
        return success;
    }

    public int getRecipesReceived() {
        return recipesReceived;
    }

    public int getRecipesNew() {
        return recipesNew;
    }

    public int getRecipesUpdated() {
        return recipesUpdated;
    }

    public int getRecipesUnchanged() {
        return recipesUnchanged;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getVersion() {
        return version;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Throwable getException() {
        return exception;
    }

    // === Formatted Summary ===

    /**
     * Get a human-readable summary of the result.
     */
    public String getSummary() {
        if (success) {
            if (recipesNew > 0 || recipesUpdated > 0) {
                return String.format("Sync complete! %,d recipes (%,d new, %,d updated, %,d unchanged)",
                        recipesReceived, recipesNew, recipesUpdated, recipesUnchanged);
            } else if (recipesReceived > 0) {
                return String.format("Sync complete! %,d recipes uploaded", recipesReceived);
            } else {
                return "Sync complete!";
            }
        } else {
            return "Sync failed: " + (errorMessage != null ? errorMessage : "Unknown error");
        }
    }

    @Override
    public String toString() {
        return "ExportResult{" +
                "success=" + success +
                ", recipesReceived=" + recipesReceived +
                ", recipesNew=" + recipesNew +
                ", recipesUpdated=" + recipesUpdated +
                ", recipesUnchanged=" + recipesUnchanged +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}

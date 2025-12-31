package com.recipeflow.mod.core.upload;

/**
 * Result of a chunked upload operation.
 * Tracks upload session details and progress.
 */
public class UploadResult {

    private final boolean success;
    private final String sessionId;
    private final int chunksUploaded;
    private final long bytesUploaded;
    private final String errorMessage;
    private final Throwable exception;

    private UploadResult(boolean success, String sessionId, int chunksUploaded,
                         long bytesUploaded, String errorMessage, Throwable exception) {
        this.success = success;
        this.sessionId = sessionId;
        this.chunksUploaded = chunksUploaded;
        this.bytesUploaded = bytesUploaded;
        this.errorMessage = errorMessage;
        this.exception = exception;
    }

    // === Factory Methods ===

    /**
     * Create a successful upload result.
     *
     * @param sessionId Session ID from the server
     * @param chunks Number of chunks successfully uploaded
     * @param bytes Total bytes successfully uploaded
     * @return Success result
     */
    public static UploadResult success(String sessionId, int chunks, long bytes) {
        return new UploadResult(true, sessionId, chunks, bytes, null, null);
    }

    /**
     * Create an error result with message and exception.
     *
     * @param message Error message
     * @param exception Exception that caused the error (may be null)
     * @return Error result
     */
    public static UploadResult error(String message, Throwable exception) {
        return new UploadResult(false, null, 0, 0, message, exception);
    }

    /**
     * Create an error result with message only.
     *
     * @param message Error message
     * @return Error result
     */
    public static UploadResult error(String message) {
        return error(message, null);
    }

    // === Getters ===

    /**
     * Check if the upload was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Get the upload session ID.
     *
     * @return Session ID, or null if upload failed
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get the number of chunks successfully uploaded.
     *
     * @return Number of chunks
     */
    public int getChunksUploaded() {
        return chunksUploaded;
    }

    /**
     * Get the total number of bytes successfully uploaded.
     *
     * @return Number of bytes
     */
    public long getBytesUploaded() {
        return bytesUploaded;
    }

    /**
     * Get the error message (if failed).
     *
     * @return Error message, or null if successful
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Get the exception that caused the error (if any).
     *
     * @return Exception, or null if no exception occurred
     */
    public Throwable getException() {
        return exception;
    }

    // === Formatted Summary ===

    /**
     * Get a human-readable summary of the upload result.
     *
     * @return Summary string for display
     */
    public String getSummary() {
        if (success) {
            if (chunksUploaded > 0) {
                return String.format("Upload complete! %d chunks (%s) uploaded (Session: %s)",
                        chunksUploaded, formatBytes(bytesUploaded), sessionId);
            } else {
                return String.format("Upload complete! (Session: %s)", sessionId);
            }
        } else {
            return "Upload failed: " + (errorMessage != null ? errorMessage : "Unknown error");
        }
    }

    /**
     * Format bytes into human-readable string (B, KB, MB, GB, TB).
     *
     * @param bytes Number of bytes
     * @return Formatted string like "1.5 MB"
     */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.1f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format("%.1f TB", tb);
    }

    @Override
    public String toString() {
        return "UploadResult{" +
                "success=" + success +
                ", sessionId='" + sessionId + '\'' +
                ", chunksUploaded=" + chunksUploaded +
                ", bytesUploaded=" + bytesUploaded +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}

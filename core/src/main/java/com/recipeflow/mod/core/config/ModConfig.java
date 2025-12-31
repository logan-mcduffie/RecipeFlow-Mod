package com.recipeflow.mod.core.config;

/**
 * Configuration interface for RecipeFlow mod settings.
 * Implementations wrap version-specific config systems (Forge Config, NightConfig).
 */
public interface ModConfig {

    // === Required Settings ===

    /**
     * Get the RecipeFlow server URL.
     * Example: "https://recipeflow.example.com"
     */
    String getServerUrl();

    /**
     * Get the authentication token from user's RecipeFlow account.
     */
    String getAuthToken();

    /**
     * Get the modpack identifier (slug from web app).
     */
    String getModpackSlug();

    // === Optional Settings with Defaults ===

    /**
     * Number of recipes per upload batch.
     */
    default int getBatchSize() {
        return 1000;
    }

    /**
     * HTTP request timeout in milliseconds.
     * Default is 5 minutes to allow for large recipe syncs.
     */
    default int getTimeoutMs() {
        return 300000; // 5 minutes
    }

    /**
     * Whether GZIP compression is enabled for uploads.
     */
    default boolean isCompressionEnabled() {
        return true;
    }

    /**
     * Whether debug logging is enabled.
     */
    default boolean isDebugLogging() {
        return false;
    }

    /**
     * Override for auto-detected modpack version.
     * Empty string means use auto-detection.
     */
    default String getVersionOverride() {
        return "";
    }

    // === Validation ===

    /**
     * Validate that all required configuration is present.
     *
     * @return ValidationResult indicating success or error with message
     */
    ValidationResult validate();

    /**
     * Result of configuration validation.
     */
    class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        /**
         * Create a successful validation result.
         */
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        /**
         * Create a failed validation result with error message.
         */
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        /**
         * Check if validation passed.
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Get the error message (null if valid).
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}

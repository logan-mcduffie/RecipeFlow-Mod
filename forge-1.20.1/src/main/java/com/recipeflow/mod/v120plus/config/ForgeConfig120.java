package com.recipeflow.mod.v120plus.config;

import com.recipeflow.mod.core.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

/**
 * Forge 1.20.1+ configuration using ForgeConfigSpec.
 * Creates config/recipeflow-common.toml
 */
public class ForgeConfig120 implements ModConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfig120 INSTANCE;

    // Config values
    private static final ForgeConfigSpec.ConfigValue<String> SERVER_URL;
    private static final ForgeConfigSpec.ConfigValue<String> AUTH_TOKEN;
    private static final ForgeConfigSpec.ConfigValue<String> MODPACK_SLUG;
    private static final ForgeConfigSpec.IntValue BATCH_SIZE;
    private static final ForgeConfigSpec.IntValue TIMEOUT_MS;
    private static final ForgeConfigSpec.BooleanValue COMPRESSION_ENABLED;
    private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;
    private static final ForgeConfigSpec.ConfigValue<String> VERSION_OVERRIDE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("RecipeFlow Server Settings").push("server");

        SERVER_URL = builder
                .comment("RecipeFlow server URL (e.g., https://recipeflow.example.com)")
                .define("url", "");

        AUTH_TOKEN = builder
                .comment("Authentication token from your RecipeFlow account settings.",
                        "Get this from your account page on the RecipeFlow web app.")
                .define("authToken", "");

        MODPACK_SLUG = builder
                .comment("Modpack identifier (slug from web app).",
                        "This is the URL-friendly name of your modpack.")
                .define("modpackSlug", "");

        builder.pop();

        builder.comment("Sync Settings").push("sync");

        BATCH_SIZE = builder
                .comment("Number of recipes per upload batch.",
                        "Larger batches are more efficient but use more memory.")
                .defineInRange("batchSize", 1000, 100, 10000);

        TIMEOUT_MS = builder
                .comment("HTTP request timeout in milliseconds.",
                        "Default is 5 minutes (300000ms) to allow for large recipe syncs.")
                .defineInRange("timeoutMs", 300000, 5000, 600000);

        COMPRESSION_ENABLED = builder
                .comment("Enable GZIP compression for uploads.",
                        "Recommended to leave enabled for faster uploads.")
                .define("compression", true);

        builder.pop();

        builder.comment("Advanced Settings").push("advanced");

        DEBUG_LOGGING = builder
                .comment("Enable debug logging for troubleshooting.")
                .define("debug", false);

        VERSION_OVERRIDE = builder
                .comment("Override auto-detected modpack version.",
                        "Leave empty to auto-detect from manifest.json or pack.toml.")
                .define("versionOverride", "");

        builder.pop();

        SPEC = builder.build();
        INSTANCE = new ForgeConfig120();
    }

    /**
     * Register the config with Forge. Call during mod construction.
     */
    @SuppressWarnings("removal")
    public static void register() {
        ModLoadingContext.get().registerConfig(Type.COMMON, SPEC, "recipeflow-common.toml");
    }

    // === ModConfig Implementation ===

    @Override
    public String getServerUrl() {
        return SERVER_URL.get();
    }

    @Override
    public String getAuthToken() {
        // Use AuthProvider to check device flow token first
        return com.recipeflow.mod.v120plus.auth.AuthProvider.INSTANCE.getAuthToken();
    }

    /**
     * Get the auth token directly from config (bypasses AuthProvider).
     * Used internally by AuthProvider to avoid circular dependency.
     */
    public String getConfigAuthToken() {
        return AUTH_TOKEN.get();
    }

    @Override
    public String getModpackSlug() {
        return MODPACK_SLUG.get();
    }

    @Override
    public int getBatchSize() {
        return BATCH_SIZE.get();
    }

    @Override
    public int getTimeoutMs() {
        return TIMEOUT_MS.get();
    }

    @Override
    public boolean isCompressionEnabled() {
        return COMPRESSION_ENABLED.get();
    }

    @Override
    public boolean isDebugLogging() {
        return DEBUG_LOGGING.get();
    }

    @Override
    public String getVersionOverride() {
        return VERSION_OVERRIDE.get();
    }

    @Override
    public ValidationResult validate() {
        String url = getServerUrl();
        if (url == null || url.trim().isEmpty()) {
            return ValidationResult.error(
                    "Server URL is not configured. Edit config/recipeflow-common.toml and set 'url' under [server]");
        }

        String token = getAuthToken();
        if (token == null || token.trim().isEmpty()) {
            return ValidationResult.error(
                    "Not authenticated. Run '/recipeflow login' or set 'authToken' in config/recipeflow-common.toml");
        }

        String slug = getModpackSlug();
        if (slug == null || slug.trim().isEmpty()) {
            return ValidationResult.error(
                    "Modpack slug is not configured. Edit config/recipeflow-common.toml and set 'modpackSlug' under [server]");
        }

        // Validate URL format
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ValidationResult.error(
                    "Server URL must start with http:// or https://");
        }

        return ValidationResult.success();
    }
}

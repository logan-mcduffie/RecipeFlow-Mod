package com.recipeflow.mod.v120plus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.api.RecipeProvider;
import com.recipeflow.mod.core.config.ModConfig;
import com.recipeflow.mod.core.export.ExportResult;
import com.recipeflow.mod.core.export.HttpExporter;
import com.recipeflow.mod.core.export.IconMetadata;
import com.recipeflow.mod.core.model.ItemMetadata;
import com.recipeflow.mod.core.registry.ProviderRegistry;
import com.recipeflow.mod.core.upload.ChunkedUploader;
import com.recipeflow.mod.core.upload.UploadResult;
import com.recipeflow.mod.core.util.ManifestHasher;
import com.recipeflow.mod.core.util.VersionDetector;
import com.recipeflow.mod.v120plus.RecipeFlowMod;
import com.recipeflow.mod.v120plus.auth.AuthProvider;
import com.recipeflow.mod.v120plus.config.ForgeConfig120;
import com.recipeflow.mod.v120plus.util.IconUploader;
import com.recipeflow.mod.v120plus.util.ItemMetadataExtractor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements /recipeflow command tree.
 * Main command: /recipeflow sync - extracts and uploads recipes to server.
 */
public class SyncCommand {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getNumberInstance();
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    // Prevent concurrent sync operations
    private static final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    // Custom executor that inherits the mod classloader context
    // Using this instead of ForkJoinPool.commonPool() avoids ClassNotFoundException errors
    // from Forge's EventSubclassTransformer trying to load classes in the wrong classloader
    private static final Executor SYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RecipeFlow-Sync");
        t.setDaemon(true);
        return t;
    });

    private SyncCommand() {
        // Utility class
    }

    /**
     * Register the /recipeflow command tree.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var recipeflowCommand = Commands.literal("recipeflow")
                .then(Commands.literal("sync")
                        .requires(source -> source.hasPermission(2)) // Require op level 2
                        .executes(SyncCommand::executeSync))
                .then(Commands.literal("status")
                        .executes(SyncCommand::executeStatus))
                .then(Commands.literal("help")
                        .executes(SyncCommand::executeHelp));

        // Register auth commands (login/logout)
        AuthCommand.registerSubcommands(recipeflowCommand);

        // Register debug icon command (client only)
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DebugIconCommand.registerSubcommand(recipeflowCommand);
        }

        dispatcher.register(recipeflowCommand);

        LOGGER.info("RecipeFlow: Registered /recipeflow command");
    }

    /**
     * Execute the sync command.
     */
    private static int executeSync(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // Check if sync is already in progress
        if (!syncInProgress.compareAndSet(false, true)) {
            sendError(source, "A sync operation is already in progress. Please wait.");
            return 0;
        }

        // Validate configuration
        ModConfig config = ForgeConfig120.INSTANCE;
        ModConfig.ValidationResult validation = config.validate();
        if (!validation.isValid()) {
            syncInProgress.set(false);
            sendError(source, validation.getErrorMessage());
            return 0;
        }

        // Icon export requires client
        boolean isClient = FMLEnvironment.dist == Dist.CLIENT;
        if (!isClient) {
            sendMessage(source, "Note: Running on dedicated server. Icon export will be skipped.");
        }

        sendMessage(source, "Starting sync...");

        // Run async to avoid blocking the main thread
        // Use custom executor to inherit mod classloader context and avoid ClassNotFoundException
        CompletableFuture.runAsync(() -> {
            try {
                executeSyncAsync(source, config, isClient);
            } catch (Exception e) {
                LOGGER.error("RecipeFlow: Sync failed with exception", e);
                sendError(source, "Sync failed: " + e.getMessage());
            } finally {
                syncInProgress.set(false);
            }
        }, SYNC_EXECUTOR);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Async sync execution.
     */
    private static void executeSyncAsync(CommandSourceStack source,
                                          ModConfig config,
                                          boolean exportIcons) {
        // Step 1: Detect modpack version
        Path gameDir = FMLPaths.GAMEDIR.get();
        String version = VersionDetector.getEffectiveVersion(gameDir, config.getVersionOverride());

        if (version == null || version.isEmpty()) {
            sendError(source, "Could not detect modpack version. Set 'versionOverride' in config/recipeflow-common.toml");
            return;
        }

        sendMessage(source, "Modpack version: " + version);

        // Step 2: Compute manifest hash (optional in dev mode)
        ManifestHasher.HashResult hashResult = ManifestHasher.computeHash(gameDir);
        String manifestHash = null;
        if (hashResult != null) {
            manifestHash = hashResult.getHash();
            sendMessage(source, "Manifest hash: " + hashResult.getHash() + " (" + hashResult.getModCount() + " mods)");
        } else {
            sendMessage(source, "No manifest file found - skipping hash (dev mode)");
        }

        // Step 3: Extract recipes
        sendMessage(source, "Extracting recipes...");

        ProviderRegistry registry = RecipeFlowMod.getInstance().getProviderRegistry();
        if (registry == null) {
            sendError(source, "Provider registry not initialized. Wait for server to fully start.");
            return;
        }

        List<RecipeData> recipes = registry.extractAllRecipes(new ProviderRegistry.ExtractionCallback() {
            @Override
            public void onProviderStart(RecipeProvider provider) {
                sendMessage(source, "  Extracting from " + provider.getProviderName() + "...");
            }

            @Override
            public void onModExtracted(RecipeProvider provider, String modId, int count) {
                sendMessage(source, "    " + modId + ": " + NUMBER_FORMAT.format(count) + " recipes");
            }

            @Override
            public void onProviderComplete(RecipeProvider provider, int count) {
                sendMessage(source, "  " + provider.getProviderName() + ": " +
                        NUMBER_FORMAT.format(count) + " recipes total");
            }

            @Override
            public void onComplete(int total, int providers) {
                sendMessage(source, "Extracted " + NUMBER_FORMAT.format(total) +
                        " recipes from " + providers + " provider(s)");
            }
        });

        if (recipes.isEmpty()) {
            sendError(source, "No recipes extracted. Check that mods are properly loaded.");
            return;
        }

        // Step 4: Export and upload icons (client only)
        // IMPORTANT: Icon export must run on the main render thread for OpenGL operations
        if (exportIcons) {
            // Check if icons already exist on server before doing expensive export
            ChunkedUploader checkUploader = new ChunkedUploader(config);
            boolean iconsExist = checkUploader.checkUploadExists(version, "icons");

            if (iconsExist) {
                sendMessage(source, "Icons already exist on server, skipping export");
            } else {
                try {
                    // Block until icon export completes on the main thread
                    java.util.concurrent.CompletableFuture<Void> iconFuture = new java.util.concurrent.CompletableFuture<>();
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        try {
                            exportAndUploadIcons(source, config, version);
                            iconFuture.complete(null);
                        } catch (Exception e) {
                            iconFuture.completeExceptionally(e);
                        }
                    });
                    iconFuture.get(); // Wait for icon export to complete
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow: Icon export failed", e);
                    sendMessage(source, "Warning: Icon export failed: " + e.getMessage());
                }
            }
        }

        // Step 4.5: Extract and upload item metadata (client only)
        if (exportIcons) {
            extractAndUploadItemMetadata(source, config, version, recipes);
        }

        // Step 5: Upload recipes to server
        sendMessage(source, "Uploading to server...");

        HttpExporter exporter = new HttpExporter(config);
        ExportResult result = exporter.syncRecipes(recipes, version, manifestHash,
                (current, total, message) -> {
                    sendMessage(source, "  " + message);
                }
        );

        // Step 6: Report result
        if (result.isSuccess()) {
            sendSuccess(source, result.getSummary());
        } else {
            sendError(source, result.getSummary());
            if (result.getException() != null && config.isDebugLogging()) {
                LOGGER.error("Sync error details", result.getException());
            }
        }
    }

    /**
     * Export and upload icons with progress feedback.
     */
    private static void exportAndUploadIcons(CommandSourceStack source, ModConfig config, String version) {
        sendMessage(source, "Exporting icons...");

        try {
            // Export icons to default directory (config/recipeflow/icons)
            Path iconDir = Path.of("config", RecipeFlowMod.MOD_ID, "icons");
            final int[] lastReportedIconPercent = {0};
            IconMetadata icons = RecipeFlowMod.getInstance().exportIcons(
                    iconDir,
                    (current, total, itemId) -> {
                        // Report progress every 15% or at completion
                        int percent = (current * 100) / total;
                        if (percent >= lastReportedIconPercent[0] + 15 || current == total) {
                            lastReportedIconPercent[0] = percent;
                            sendMessage(source, String.format("  Exporting icons... %d%% (%s/%s)",
                                    percent,
                                    NUMBER_FORMAT.format(current),
                                    NUMBER_FORMAT.format(total)));
                        }
                    }
            );
            sendMessage(source, "Exported " + NUMBER_FORMAT.format(icons.size()) + " icons");

            // Upload icons to server
            sendMessage(source, "Uploading icons...");
            IconUploader uploader = new IconUploader(config);
            UploadResult uploadResult = uploader.uploadIcons(
                    iconDir,
                    icons,
                    version,
                    (current, total, message) -> {
                        sendMessage(source, "  " + message);
                    }
            );

            // Report upload result
            if (uploadResult.isSuccess()) {
                sendSuccess(source, uploadResult.getSummary());
            } else {
                sendError(source, "Icon upload failed: " + uploadResult.getErrorMessage());
                if (uploadResult.getException() != null && config.isDebugLogging()) {
                    LOGGER.error("Icon upload error details", uploadResult.getException());
                }
            }

        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Icon export/upload failed", e);
            sendMessage(source, "Warning: Icon export/upload failed: " + e.getMessage());
            sendMessage(source, "Continuing with recipe sync...");
            // Continue with recipe sync even if icon export/upload fails
        }
    }

    /**
     * Extract and upload item metadata with progress feedback.
     */
    private static void extractAndUploadItemMetadata(CommandSourceStack source, ModConfig config,
                                                      String version, List<RecipeData> recipes) {
        sendMessage(source, "Extracting item metadata...");

        try {
            // Extract metadata from recipes
            final int[] lastReportedPercent = {0};
            java.util.Map<String, ItemMetadata> metadata = ItemMetadataExtractor.extractFromRecipes(
                    recipes,
                    (current, total, itemId) -> {
                        // Report progress every 15% or at completion
                        int percent = (current * 100) / total;
                        if (percent >= lastReportedPercent[0] + 15 || current == total) {
                            lastReportedPercent[0] = percent;
                            sendMessage(source, String.format("  Extracting metadata... %d%% (%s/%s)",
                                    percent,
                                    NUMBER_FORMAT.format(current),
                                    NUMBER_FORMAT.format(total)));
                        }
                    }
            );
            sendMessage(source, "Extracted metadata for " + NUMBER_FORMAT.format(metadata.size()) + " items");

            // Serialize to JSON
            java.util.List<java.util.Map<String, Object>> metadataList = new java.util.ArrayList<>();
            for (ItemMetadata item : metadata.values()) {
                metadataList.add(item.toJsonMap());
            }
            String json = GSON.toJson(metadataList);
            byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            // Upload via chunked uploader (2MB chunks for item metadata)
            sendMessage(source, "Uploading item metadata...");
            int chunkSize = 2 * 1024 * 1024; // 2MB chunks
            ChunkedUploader uploader = new ChunkedUploader(config, chunkSize);
            UploadResult uploadResult = uploader.upload(
                    data,
                    version,
                    "items",
                    (current, total, message) -> {
                        sendMessage(source, "  " + message);
                    }
            );

            // Report upload result
            if (uploadResult.isSuccess()) {
                sendSuccess(source, "Item metadata uploaded successfully (" +
                        formatBytes(uploadResult.getBytesUploaded()) + ")");
            } else {
                sendError(source, "Item metadata upload failed: " + uploadResult.getErrorMessage());
                if (uploadResult.getException() != null && config.isDebugLogging()) {
                    LOGGER.error("Item metadata upload error details", uploadResult.getException());
                }
            }

        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Item metadata extraction/upload failed", e);
            sendMessage(source, "Warning: Item metadata extraction/upload failed: " + e.getMessage());
            sendMessage(source, "Continuing with recipe sync...");
            // Continue with recipe sync even if item metadata fails
        }
    }

    /**
     * Execute status command - show current config and status.
     */
    private static int executeStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ModConfig config = ForgeConfig120.INSTANCE;

        sendMessage(source, "=== RecipeFlow Status ===");
        sendMessage(source, "Server URL: " + maskUrl(config.getServerUrl()));

        // Show auth status with source
        AuthProvider auth = AuthProvider.INSTANCE;
        if (auth.isAuthenticated()) {
            String source_type = auth.getAuthSource();
            String expInfo = auth.getTokenExpirationInfo();
            String authStatus = "Authenticated via " + source_type;
            if (expInfo != null && auth.isDeviceFlowAuthenticated()) {
                authStatus += " (" + expInfo + ")";
            }
            sendMessage(source, "Auth: " + authStatus);
        } else {
            sendMessage(source, "Auth: Not authenticated");
        }

        sendMessage(source, "Modpack Slug: " + (config.getModpackSlug().isEmpty() ? "(not set)" : config.getModpackSlug()));
        sendMessage(source, "Compression: " + (config.isCompressionEnabled() ? "enabled" : "disabled"));
        sendMessage(source, "Debug Logging: " + (config.isDebugLogging() ? "enabled" : "disabled"));

        Path gameDir = FMLPaths.GAMEDIR.get();
        String detectedVersion = VersionDetector.detectVersion(gameDir);
        String versionOverride = config.getVersionOverride();

        sendMessage(source, "Auto-detected Version: " + (detectedVersion != null ? detectedVersion : "(none)"));
        if (!versionOverride.isEmpty()) {
            sendMessage(source, "Version Override: " + versionOverride);
        }

        ProviderRegistry registry = RecipeFlowMod.getInstance().getProviderRegistry();
        if (registry != null) {
            sendMessage(source, "Registered Providers: " + registry.size());
        }

        sendMessage(source, "Sync in Progress: " + (syncInProgress.get() ? "yes" : "no"));

        // Validation status
        ModConfig.ValidationResult validation = config.validate();
        if (validation.isValid()) {
            sendSuccess(source, "Configuration is valid. Ready to sync.");
        } else {
            sendError(source, validation.getErrorMessage());
        }

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Execute help command.
     */
    private static int executeHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        sendMessage(source, "=== RecipeFlow Commands ===");
        sendMessage(source, "/recipeflow sync - Extract and upload recipes to server");
        sendMessage(source, "/recipeflow login - Authenticate via browser (Discord)");
        sendMessage(source, "/recipeflow logout - Clear stored authentication");
        sendMessage(source, "/recipeflow status - Show current configuration and status");
        sendMessage(source, "/recipeflow help - Show this help message");
        sendMessage(source, "");
        sendMessage(source, "Configuration: config/recipeflow-common.toml");
        sendMessage(source, "Required: url, modpackSlug (auth via /recipeflow login or authToken)");

        return Command.SINGLE_SUCCESS;
    }

    // === Helper Methods ===

    private static void sendMessage(CommandSourceStack source, String message) {
        try {
            source.sendSystemMessage(Component.literal("[RecipeFlow] " + message));
        } catch (Exception e) {
            // Fallback to logger if sending to player fails
            LOGGER.info("[RecipeFlow] " + message);
        }
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        try {
            source.sendSuccess(() -> Component.literal("[RecipeFlow] " + message), true);
        } catch (Exception e) {
            LOGGER.info("[RecipeFlow] " + message);
        }
    }

    private static void sendError(CommandSourceStack source, String message) {
        try {
            source.sendFailure(Component.literal("[RecipeFlow] " + message));
        } catch (Exception e) {
            LOGGER.error("[RecipeFlow] " + message);
        }
    }

    private static String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "(not set)";
        }
        // Show just the domain portion
        try {
            java.net.URL parsed = new java.net.URL(url);
            return parsed.getProtocol() + "://" + parsed.getHost() +
                    (parsed.getPort() > 0 ? ":" + parsed.getPort() : "") + "/...";
        } catch (Exception e) {
            return url.length() > 30 ? url.substring(0, 30) + "..." : url;
        }
    }

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
}

package com.recipeflow.mod.v120plus;

import com.recipeflow.mod.core.export.IconMetadata;
import com.recipeflow.mod.core.registry.ProviderRegistry;
import com.recipeflow.mod.v120plus.command.SyncCommand;
import com.recipeflow.mod.v120plus.config.ForgeConfig120;
import com.recipeflow.mod.v120plus.provider.GTCEuRecipeProvider;
import com.recipeflow.mod.v120plus.provider.StandardRecipeProvider;
import com.recipeflow.mod.v120plus.util.IconExporter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * RecipeFlow Companion Mod - 1.20.1 Entry Point
 *
 * Extracts recipes from modpacks and syncs them to the RecipeFlow web application.
 */
@Mod(RecipeFlowMod.MOD_ID)
public class RecipeFlowMod {

    public static final String MOD_ID = "recipeflow";
    private static final Logger LOGGER = LogManager.getLogger();

    private static RecipeFlowMod instance;
    private ProviderRegistry providerRegistry;

    public RecipeFlowMod() {
        instance = this;
        MinecraftForge.EVENT_BUS.register(this);

        // Register configuration
        ForgeConfig120.register();

        LOGGER.info("RecipeFlow Companion Mod initializing...");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("RecipeFlow: Server started, initializing recipe providers...");

        providerRegistry = new ProviderRegistry();

        // Register providers in priority order (highest first)
        // GTCEu direct API - Priority 100 (for GT machine recipes)
        providerRegistry.register(new GTCEuRecipeProvider());

        // Standard Minecraft RecipeManager - Priority 10 (covers all mods using standard recipe system)
        providerRegistry.register(new StandardRecipeProvider());

        LOGGER.info("RecipeFlow: Registered {} recipe providers", providerRegistry.size());
    }

    /**
     * Register commands when the server starts.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SyncCommand.register(event.getDispatcher());
    }

    /**
     * Get the mod instance.
     */
    public static RecipeFlowMod getInstance() {
        return instance;
    }

    /**
     * Get the provider registry.
     */
    public ProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    /**
     * Export all item icons to the specified directory.
     * Must be called on the client side (requires rendering context).
     *
     * @param outputDir The directory to save icons to
     * @param callback Progress callback (may be null)
     * @return IconMetadata containing information about all exported icons
     * @throws IllegalStateException if called on a dedicated server
     */
    public IconMetadata exportIcons(Path outputDir, IconExporter.ExportCallback callback) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            throw new IllegalStateException("Icon export must be run on the client side");
        }

        LOGGER.info("RecipeFlow: Starting icon export to {}", outputDir);
        IconExporter exporter = new IconExporter();
        return exporter.exportAllIcons(outputDir, callback);
    }

    /**
     * Export all item icons to the default icons directory.
     * Must be called on the client side (requires rendering context).
     *
     * @param callback Progress callback (may be null)
     * @return IconMetadata containing information about all exported icons
     */
    public IconMetadata exportIcons(IconExporter.ExportCallback callback) {
        Path defaultDir = Path.of("config", MOD_ID, "icons");
        return exportIcons(defaultDir, callback);
    }
}

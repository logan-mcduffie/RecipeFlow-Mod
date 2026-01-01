package com.recipeflow.mod.v120plus;

import com.recipeflow.mod.core.registry.ProviderRegistry;
import com.recipeflow.mod.v120plus.command.SyncCommand;
import com.recipeflow.mod.v120plus.config.ForgeConfig120;
import com.recipeflow.mod.v120plus.provider.GTCEuRecipeProvider;
import com.recipeflow.mod.v120plus.provider.StandardRecipeProvider;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
}

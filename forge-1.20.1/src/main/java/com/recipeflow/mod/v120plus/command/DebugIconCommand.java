package com.recipeflow.mod.v120plus.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.recipeflow.mod.v120plus.util.AnimatedIconRenderer;
import com.recipeflow.mod.v120plus.util.SimpleRenderModeHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import com.recipeflow.mod.v120plus.util.IconExporter;
import com.recipeflow.mod.v120plus.util.FluidIconRenderer;
import com.recipeflow.mod.core.export.IconMetadata;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/**
 * Debug command for testing icon rendering.
 * Usage: /recipeflow debugicon <item_id>
 * Example: /recipeflow debugicon gtceu:luv_fusion_reactor
 *
 * This command demonstrates the same rendering path used by the mass export.
 * When GTCEu's SimpleRenderMode API is available, it enables simple rendering
 * to ensure animated textures render correctly for capture.
 *
 * Renders the specified item and saves it to config/recipeflow/debug/
 */
@OnlyIn(Dist.CLIENT)
public class DebugIconCommand {

    private static final Logger LOGGER = LogManager.getLogger();

    private DebugIconCommand() {
        // Utility class
    }

    /**
     * Suggestion provider for item IDs with tab completion.
     * Suggests all registered items, filtering by what the user has typed.
     */
    private static final SuggestionProvider<CommandSourceStack> ITEM_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();

        // Get all item IDs from the registry
        Iterable<ResourceLocation> itemIds = ForgeRegistries.ITEMS.getKeys();

        // Filter and suggest matching items
        return SharedSuggestionProvider.suggest(
            StreamSupport.stream(itemIds.spliterator(), false)
                .map(ResourceLocation::toString)
                .filter(id -> id.toLowerCase().contains(remaining))
                .sorted((a, b) -> {
                    // Prioritize items that start with the typed text
                    boolean aStarts = a.toLowerCase().startsWith(remaining);
                    boolean bStarts = b.toLowerCase().startsWith(remaining);
                    if (aStarts && !bStarts) return -1;
                    if (!aStarts && bStarts) return 1;
                    return a.compareTo(b);
                })
                .limit(50), // Limit suggestions to avoid overwhelming the UI
            builder
        );
    };

    /**
     * Suggestion provider for fluid IDs with tab completion.
     * Suggests all registered fluids, filtering by what the user has typed.
     */
    private static final SuggestionProvider<CommandSourceStack> FLUID_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining().toLowerCase();

        // Get all fluid IDs from the registry
        Iterable<ResourceLocation> fluidIds = ForgeRegistries.FLUIDS.getKeys();

        // Filter and suggest matching fluids
        return SharedSuggestionProvider.suggest(
            StreamSupport.stream(fluidIds.spliterator(), false)
                .map(ResourceLocation::toString)
                .filter(id -> id.toLowerCase().contains(remaining))
                .sorted((a, b) -> {
                    // Prioritize fluids that start with the typed text
                    boolean aStarts = a.toLowerCase().startsWith(remaining);
                    boolean bStarts = b.toLowerCase().startsWith(remaining);
                    if (aStarts && !bStarts) return -1;
                    if (!aStarts && bStarts) return 1;
                    return a.compareTo(b);
                })
                .limit(50), // Limit suggestions to avoid overwhelming the UI
            builder
        );
    };

    /**
     * Register the debugicon subcommand.
     */
    public static void registerSubcommand(LiteralArgumentBuilder<CommandSourceStack> parent) {
        parent.then(Commands.literal("debugicon")
                .then(Commands.argument("item", StringArgumentType.greedyString())
                        .suggests(ITEM_SUGGESTIONS)
                        .executes(DebugIconCommand::executeDebugIcon)));

        // Register debugfluid subcommand
        parent.then(Commands.literal("debugfluid")
                .then(Commands.argument("fluid", StringArgumentType.greedyString())
                        .suggests(FLUID_SUGGESTIONS)
                        .executes(DebugIconCommand::executeDebugFluid)));
    }

    /**
     * Execute the debug icon command.
     */
    private static int executeDebugIcon(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String itemIdStr = StringArgumentType.getString(context, "item");

        // Parse item ID
        ResourceLocation itemId;
        try {
            itemId = ResourceLocation.tryParse(itemIdStr);
            if (itemId == null) {
                sendError(source, "Invalid item ID format: " + itemIdStr);
                return 0;
            }
        } catch (Exception e) {
            sendError(source, "Invalid item ID format: " + itemIdStr);
            return 0;
        }

        // Look up item
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            sendError(source, "Item not found: " + itemIdStr);
            return 0;
        }

        ItemStack stack = new ItemStack(item, 1);

        sendMessage(source, "Rendering icon for: " + itemIdStr);

        // Log SimpleRenderMode availability
        if (SimpleRenderModeHelper.isAvailable()) {
            sendMessage(source, "GTCEu SimpleRenderMode: available");
        } else {
            sendMessage(source, "GTCEu SimpleRenderMode: not available (GTCEu not installed or old version)");
        }

        // Run on render thread
        Minecraft.getInstance().execute(() -> {
            try {
                renderAndSaveIcon(source, stack, itemId);
            } catch (Exception e) {
                LOGGER.error("Failed to render debug icon", e);
                sendError(source, "Failed to render: " + e.getMessage());
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Render and save the icon.
     * Uses SimpleRenderMode when available for proper GTCEu machine animation capture.
     */
    private static void renderAndSaveIcon(CommandSourceStack source, ItemStack stack, ResourceLocation itemId) {
        AnimatedIconRenderer renderer = new AnimatedIconRenderer();

        try {
            // Get model info for debugging
            var itemRenderer = Minecraft.getInstance().getItemRenderer();
            BakedModel model = itemRenderer.getModel(stack, null, null, 0);

            sendMessage(source, "Model info:");
            sendMessage(source, "  modelClass: " + model.getClass().getName());
            sendMessage(source, "  isGui3d: " + model.isGui3d());
            sendMessage(source, "  usesBlockLight: " + model.usesBlockLight());

            // Debug: show all sprites found from the model
            sendMessage(source, "Item Model Sprites:");
            Set<TextureAtlasSprite> sprites = new HashSet<>();

            // Particle icon
            TextureAtlasSprite particleIcon = model.getParticleIcon(ModelData.EMPTY);
            if (particleIcon != null) {
                sprites.add(particleIcon);
                long frames = particleIcon.contents().getUniqueFrames().count();
                sendMessage(source, "  particle: " + particleIcon.contents().name() + " (" + frames + " frames)");
            }

            // Null direction quads
            RandomSource random = RandomSource.create();
            List<BakedQuad> nullQuads = model.getQuads(null, null, random, ModelData.EMPTY, null);
            sendMessage(source, "  nullQuads count: " + nullQuads.size());
            for (BakedQuad quad : nullQuads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite != null && sprites.add(sprite)) {
                    long frames = sprite.contents().getUniqueFrames().count();
                    sendMessage(source, "    quad: " + sprite.contents().name() + " (" + frames + " frames)");
                }
            }

            // Directional quads
            for (Direction dir : Direction.values()) {
                List<BakedQuad> dirQuads = model.getQuads(null, dir, random, ModelData.EMPTY, null);
                for (BakedQuad quad : dirQuads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite != null && sprites.add(sprite)) {
                        long frames = sprite.contents().getUniqueFrames().count();
                        sendMessage(source, "    " + dir + ": " + sprite.contents().name() + " (" + frames + " frames)");
                    }
                }
            }

            sendMessage(source, "  total unique item sprites: " + sprites.size());

            // Check if animated
            boolean isAnimated = renderer.isAnimated(stack);
            int frameCount = renderer.getFrameCount(stack);
            sendMessage(source, "  isAnimated: " + isAnimated);
            sendMessage(source, "  frameCount: " + frameCount);

            // Save to debug directory
            Path debugDir = Path.of("config", "recipeflow", "debug");
            debugDir.toFile().mkdirs();

            // Enable SimpleRenderMode for the actual export
            // This ensures GTCEu machines render with normal animation instead of emissive effects
            SimpleRenderModeHelper.enable();
            try {
                if (isAnimated) {
                    // Use IconExporter to save as GIF
                    sendMessage(source, "Exporting as animated GIF (with SimpleRenderMode)...");
                    IconExporter exporter = new IconExporter();
                    IconMetadata.IconEntry entry = exporter.exportIcon(stack, itemId, debugDir);

                    if (entry != null) {
                        sendSuccess(source, "Saved to: " + debugDir.resolve(entry.getFilename()).toAbsolutePath());
                        sendMessage(source, "Format: " + (entry.getFilename().endsWith(".gif") ? "Animated GIF" : "PNG (fallback)"));
                        sendMessage(source, "Frames: " + entry.getFrameCount());
                    } else {
                        sendError(source, "Failed to export animated icon");
                    }
                } else {
                    // Render static PNG
                    BufferedImage image = renderer.renderItem(stack);
                    String filename = itemId.getNamespace() + "_" + itemId.getPath().replace("/", "_") + ".png";
                    File outputFile = debugDir.resolve(filename).toFile();

                    ImageIO.write(image, "PNG", outputFile);

                    sendSuccess(source, "Saved to: " + outputFile.getAbsolutePath());
                    sendMessage(source, "Image size: " + image.getWidth() + "x" + image.getHeight());
                }
            } finally {
                SimpleRenderModeHelper.disable();
            }

        } catch (Exception e) {
            LOGGER.error("Failed to render debug icon for " + itemId, e);
            sendError(source, "Render failed: " + e.getMessage());
        } finally {
            renderer.dispose();
        }
    }

    /**
     * Execute the debug fluid command.
     */
    private static int executeDebugFluid(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String fluidIdStr = StringArgumentType.getString(context, "fluid");

        // Parse fluid ID
        ResourceLocation fluidId;
        try {
            fluidId = ResourceLocation.tryParse(fluidIdStr);
            if (fluidId == null) {
                sendError(source, "Invalid fluid ID format: " + fluidIdStr);
                return 0;
            }
        } catch (Exception e) {
            sendError(source, "Invalid fluid ID format: " + fluidIdStr);
            return 0;
        }

        // Look up fluid
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
        if (fluid == null || fluid == Fluids.EMPTY) {
            sendError(source, "Fluid not found: " + fluidIdStr);
            return 0;
        }

        sendMessage(source, "Rendering fluid icon for: " + fluidIdStr);

        // Run on render thread
        Minecraft.getInstance().execute(() -> {
            try {
                renderAndSaveFluidIcon(source, fluid, fluidId);
            } catch (Exception e) {
                LOGGER.error("Failed to render debug fluid icon", e);
                sendError(source, "Failed to render: " + e.getMessage());
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Render and save the fluid icon.
     */
    private static void renderAndSaveFluidIcon(CommandSourceStack source, Fluid fluid, ResourceLocation fluidId) {
        FluidIconRenderer fluidRenderer = new FluidIconRenderer();

        try {
            // Get fluid sprite info for debugging
            var sprite = fluidRenderer.getFluidSprite(fluid);
            int tintColor = fluidRenderer.getFluidTintColor(fluid);

            sendMessage(source, "Fluid info:");
            if (sprite != null) {
                sendMessage(source, "  sprite: " + sprite.contents().name());
                long frames = sprite.contents().getUniqueFrames().count();
                sendMessage(source, "  frames: " + frames);
                sendMessage(source, "  size: " + sprite.contents().width() + "x" + sprite.contents().height());
            } else {
                sendMessage(source, "  sprite: not found");
            }
            sendMessage(source, "  tintColor: 0x" + Integer.toHexString(tintColor).toUpperCase());

            // Check if animated
            boolean isAnimated = fluidRenderer.isAnimated(fluid);
            sendMessage(source, "  isAnimated: " + isAnimated);

            // Save to debug directory
            Path debugDir = Path.of("config", "recipeflow", "debug");
            debugDir.toFile().mkdirs();

            IconExporter exporter = new IconExporter();
            IconMetadata.IconEntry entry = exporter.exportFluidIcon(fluid, fluidId, debugDir);

            if (entry != null) {
                sendSuccess(source, "Saved to: " + debugDir.resolve(entry.getFilename()).toAbsolutePath());
                sendMessage(source, "Format: " + (entry.getFilename().endsWith(".gif") ? "Animated GIF" : "PNG"));
                if (isAnimated) {
                    sendMessage(source, "Frames: " + entry.getFrameCount());
                    sendMessage(source, "Frame time: " + entry.getFrameTimeMs() + "ms");
                }
            } else {
                sendError(source, "Failed to export fluid icon");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to render debug fluid icon for " + fluidId, e);
            sendError(source, "Render failed: " + e.getMessage());
        }
    }

    // === Helper Methods ===

    private static void sendMessage(CommandSourceStack source, String message) {
        try {
            source.sendSystemMessage(Component.literal("[RecipeFlow] " + message));
        } catch (Exception e) {
            LOGGER.info("[RecipeFlow] " + message);
        }
    }

    private static void sendSuccess(CommandSourceStack source, String message) {
        try {
            source.sendSuccess(() -> Component.literal("[RecipeFlow] " + message), false);
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
}

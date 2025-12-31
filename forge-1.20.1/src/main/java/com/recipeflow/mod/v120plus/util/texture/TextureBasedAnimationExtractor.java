package com.recipeflow.mod.v120plus.util.texture;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * High-level texture-based animation extraction for GTCEu machines.
 *
 * This class provides the main API for extracting animations by:
 * 1. Extracting raw texture files from ResourceManager
 * 2. Reading .mcmeta animation metadata
 * 3. Compositing multiple texture layers (base casing + overlays + emissive)
 *
 * This approach bypasses the standard OpenGL framebuffer capture which doesn't
 * properly capture GTCEu's layered emissive overlays.
 */
@OnlyIn(Dist.CLIENT)
public class TextureBasedAnimationExtractor {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int BLOCK_ICON_SIZE = 256;

    private final TextureLayerExtractor layerExtractor;

    public TextureBasedAnimationExtractor() {
        this.layerExtractor = new TextureLayerExtractor();
    }

    /**
     * Result of animation extraction.
     */
    public static class AnimationResult {
        private final List<BufferedImage> frames;
        private final List<Integer> frameDurationsMs;
        private final boolean success;
        private final String errorMessage;

        private AnimationResult(List<BufferedImage> frames, List<Integer> frameDurationsMs,
                                boolean success, String errorMessage) {
            this.frames = frames;
            this.frameDurationsMs = frameDurationsMs;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static AnimationResult success(List<BufferedImage> frames, List<Integer> frameDurationsMs) {
            return new AnimationResult(frames, frameDurationsMs, true, null);
        }

        public static AnimationResult failure(String errorMessage) {
            return new AnimationResult(new ArrayList<>(), new ArrayList<>(), false, errorMessage);
        }

        public List<BufferedImage> getFrames() {
            return frames;
        }

        public List<Integer> getFrameDurationsMs() {
            return frameDurationsMs;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getFrameCount() {
            return frames.size();
        }

        public boolean isEmpty() {
            return frames.isEmpty();
        }
    }

    /**
     * Extract animation for a GTCEu machine using texture-based approach.
     *
     * @param stack    The item stack (must be a GTCEu machine)
     * @param iconSize The target icon size (128 for items, 256 for blocks)
     * @return AnimationResult containing frames and timing
     */
    public AnimationResult extractMachineAnimation(ItemStack stack, int iconSize) {
        if (stack.isEmpty()) {
            return AnimationResult.failure("Empty item stack");
        }

        try {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) {
                return AnimationResult.failure("Item not registered");
            }

            String machineId = itemId.getPath();
            LOGGER.info("RecipeFlow: Extracting texture-based animation for GTCEu machine: {}", machineId);

            // Step 1: Extract texture layers from the model
            List<TextureLayerExtractor.TextureLayer> modelLayers = layerExtractor.extractTextureLayers(stack);
            LOGGER.debug("RecipeFlow: Found {} model texture layers", modelLayers.size());

            // Step 2: Extract GTCEu-specific overlay layers
            List<TextureLayerExtractor.TextureLayer> overlayLayers =
                    layerExtractor.extractGTCEuOverlayLayers(machineId);
            LOGGER.debug("RecipeFlow: Found {} GTCEu overlay layers", overlayLayers.size());

            // Combine layers, prioritizing animated ones
            List<TextureLayerExtractor.TextureLayer> allLayers = new ArrayList<>();
            List<TextureLayerExtractor.TextureLayer> animatedLayers = new ArrayList<>();

            // Add model layers
            for (TextureLayerExtractor.TextureLayer layer : modelLayers) {
                allLayers.add(layer);
                if (layer.isAnimated()) {
                    animatedLayers.add(layer);
                }
            }

            // Add overlay layers (these are often the animated emissive parts)
            for (TextureLayerExtractor.TextureLayer layer : overlayLayers) {
                // Avoid duplicates
                boolean isDuplicate = false;
                for (TextureLayerExtractor.TextureLayer existing : allLayers) {
                    if (existing.getTextureLocation().equals(layer.getTextureLocation())) {
                        isDuplicate = true;
                        break;
                    }
                }
                if (!isDuplicate) {
                    allLayers.add(layer);
                    if (layer.isAnimated()) {
                        animatedLayers.add(layer);
                    }
                }
            }

            LOGGER.info("RecipeFlow: Total {} layers, {} animated for {}",
                    allLayers.size(), animatedLayers.size(), machineId);

            if (animatedLayers.isEmpty()) {
                return AnimationResult.failure("No animated layers found");
            }

            // Step 3: Create compositor and layer configs
            TextureLayerCompositor compositor = new TextureLayerCompositor(iconSize);
            List<TextureLayerCompositor.LayerConfig> layerConfigs = new ArrayList<>();

            for (TextureLayerExtractor.TextureLayer layer : allLayers) {
                TextureLayerCompositor.BlendMode blendMode = layer.isEmissive()
                        ? TextureLayerCompositor.BlendMode.ADDITIVE
                        : TextureLayerCompositor.BlendMode.NORMAL;

                layerConfigs.add(new TextureLayerCompositor.LayerConfig(layer, blendMode));
            }

            // Step 4: Composite all frames
            TextureLayerCompositor.CompositeResult result = compositor.composite(layerConfigs);

            if (result.isEmpty()) {
                return AnimationResult.failure("Compositing produced no frames");
            }

            LOGGER.info("RecipeFlow: Successfully extracted {} frames for {} via texture-based approach",
                    result.getFrameCount(), machineId);

            // Clean up native resources
            for (TextureLayerExtractor.TextureLayer layer : allLayers) {
                layer.close();
            }

            return AnimationResult.success(result.getFrames(), result.getFrameDurationsMs());

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Failed to extract texture-based animation: {}", e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
            return AnimationResult.failure(e.getMessage());
        }
    }

    /**
     * Extract animation for a GTCEu machine with default icon size.
     */
    public AnimationResult extractMachineAnimation(ItemStack stack) {
        // Use block size for machines (they're 3D blocks)
        return extractMachineAnimation(stack, BLOCK_ICON_SIZE);
    }

    /**
     * Check if an item can be processed using texture-based animation extraction.
     * Currently only supports GTCEu machines.
     *
     * @param stack The item stack to check
     * @return true if this item can use texture-based extraction
     */
    public boolean canExtract(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        try {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) {
                return false;
            }

            // Currently only GTCEu machines are supported
            return itemId.getNamespace().equals("gtceu");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract animation from specific overlay texture paths.
     * Useful when you know exactly which textures to use.
     *
     * @param overlayPaths List of texture paths (e.g., "gtceu:block/overlay/machine/overlay_pipe_in")
     * @param iconSize     Target icon size
     * @return AnimationResult containing frames and timing
     */
    public AnimationResult extractFromOverlayPaths(List<String> overlayPaths, int iconSize) {
        List<TextureLayerExtractor.TextureLayer> layers = new ArrayList<>();

        for (String path : overlayPaths) {
            boolean isEmissive = path.contains("_emissive");
            TextureLayerExtractor.TextureLayer layer = layerExtractor.extractLayerFromPath(path, isEmissive);
            if (layer != null) {
                layers.add(layer);
                LOGGER.debug("RecipeFlow: Loaded overlay layer: {} ({} frames, emissive: {})",
                        path, layer.getFrameCount(), isEmissive);
            } else {
                LOGGER.debug("RecipeFlow: Failed to load overlay layer: {}", path);
            }
        }

        if (layers.isEmpty()) {
            return AnimationResult.failure("No overlay textures found");
        }

        // Find animated layers
        List<TextureLayerExtractor.TextureLayer> animatedLayers = new ArrayList<>();
        for (TextureLayerExtractor.TextureLayer layer : layers) {
            if (layer.isAnimated()) {
                animatedLayers.add(layer);
            }
        }

        if (animatedLayers.isEmpty()) {
            // Clean up
            for (TextureLayerExtractor.TextureLayer layer : layers) {
                layer.close();
            }
            return AnimationResult.failure("No animated overlays found");
        }

        // Create compositor
        TextureLayerCompositor compositor = new TextureLayerCompositor(iconSize);
        List<TextureLayerCompositor.LayerConfig> layerConfigs = new ArrayList<>();

        for (TextureLayerExtractor.TextureLayer layer : layers) {
            TextureLayerCompositor.BlendMode blendMode = layer.isEmissive()
                    ? TextureLayerCompositor.BlendMode.ADDITIVE
                    : TextureLayerCompositor.BlendMode.NORMAL;
            layerConfigs.add(new TextureLayerCompositor.LayerConfig(layer, blendMode));
        }

        TextureLayerCompositor.CompositeResult result = compositor.composite(layerConfigs);

        // Clean up
        for (TextureLayerExtractor.TextureLayer layer : layers) {
            layer.close();
        }

        if (result.isEmpty()) {
            return AnimationResult.failure("Compositing produced no frames");
        }

        return AnimationResult.success(result.getFrames(), result.getFrameDurationsMs());
    }

    /**
     * Get frame count and timing info without fully extracting the animation.
     * Useful for checking if texture-based extraction would find animation.
     *
     * @param stack The item stack
     * @return Number of animation frames found, or 0 if not animated
     */
    public int probeAnimationFrameCount(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        try {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (itemId == null) {
                return 0;
            }

            String machineId = itemId.getPath();

            // Check GTCEu overlays
            List<TextureLayerExtractor.TextureLayer> overlayLayers =
                    layerExtractor.extractGTCEuOverlayLayers(machineId);

            int maxFrames = 0;
            for (TextureLayerExtractor.TextureLayer layer : overlayLayers) {
                if (layer.isAnimated() && layer.getFrameCount() > maxFrames) {
                    maxFrames = layer.getFrameCount();
                }
                layer.close();
            }

            return maxFrames;

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Error probing animation: {}", e.getMessage());
            return 0;
        }
    }
}

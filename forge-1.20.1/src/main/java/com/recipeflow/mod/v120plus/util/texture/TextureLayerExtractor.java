package com.recipeflow.mod.v120plus.util.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Extracts raw texture data from Minecraft's ResourceManager for texture-based animation.
 *
 * This class provides direct access to texture PNG files and their animation metadata,
 * bypassing the standard rendering pipeline which doesn't properly capture layered
 * emissive overlays used by mods like GTCEu.
 */
@OnlyIn(Dist.CLIENT)
public class TextureLayerExtractor {

    private static final Logger LOGGER = LogManager.getLogger();

    private final Minecraft minecraft;
    private final ResourceManager resourceManager;

    public TextureLayerExtractor() {
        this.minecraft = Minecraft.getInstance();
        this.resourceManager = minecraft.getResourceManager();
    }

    /**
     * Represents a single texture layer with its raw image data and animation metadata.
     */
    public static class TextureLayer {
        private final ResourceLocation textureLocation;
        private final NativeImage rawImage;
        private final McmetaParser.AnimationMetadata animationMetadata;
        private final TextureAtlasSprite sprite;
        private final boolean isEmissive;
        private final int frameWidth;
        private final int frameHeight;
        private final int frameCount;

        public TextureLayer(ResourceLocation textureLocation, NativeImage rawImage,
                            McmetaParser.AnimationMetadata animationMetadata,
                            TextureAtlasSprite sprite, boolean isEmissive) {
            this.textureLocation = textureLocation;
            this.rawImage = rawImage;
            this.animationMetadata = animationMetadata;
            this.sprite = sprite;
            this.isEmissive = isEmissive;

            // Calculate frame dimensions
            this.frameWidth = rawImage.getWidth();
            if (animationMetadata != null && animationMetadata.frameCount() > 0) {
                this.frameCount = animationMetadata.frameCount();
                this.frameHeight = rawImage.getHeight() / this.frameCount;
            } else {
                // Check if image is a vertical sprite sheet (height > width)
                if (rawImage.getHeight() > rawImage.getWidth() && rawImage.getHeight() % rawImage.getWidth() == 0) {
                    this.frameCount = rawImage.getHeight() / rawImage.getWidth();
                    this.frameHeight = rawImage.getWidth(); // Square frames
                } else {
                    this.frameCount = 1;
                    this.frameHeight = rawImage.getHeight();
                }
            }
        }

        public ResourceLocation getTextureLocation() {
            return textureLocation;
        }

        public NativeImage getRawImage() {
            return rawImage;
        }

        public McmetaParser.AnimationMetadata getAnimationMetadata() {
            return animationMetadata;
        }

        public TextureAtlasSprite getSprite() {
            return sprite;
        }

        public boolean isEmissive() {
            return isEmissive;
        }

        public int getFrameWidth() {
            return frameWidth;
        }

        public int getFrameHeight() {
            return frameHeight;
        }

        public int getFrameCount() {
            return frameCount;
        }

        public boolean isAnimated() {
            return frameCount > 1;
        }

        /**
         * Get the frame duration in milliseconds.
         * Returns the default frame time if no specific timing is set.
         */
        public int getFrameTimeMs() {
            if (animationMetadata != null) {
                return animationMetadata.getDefaultFrameTimeMs();
            }
            return 100; // Default: 2 ticks = 100ms
        }

        /**
         * Get the duration for a specific frame in milliseconds.
         */
        public int getFrameTimeMs(int frameIndex) {
            if (animationMetadata != null) {
                return animationMetadata.getFrameTimeMs(frameIndex);
            }
            return 100;
        }

        /**
         * Extract a single frame from the texture as a BufferedImage.
         *
         * @param frameIndex The frame index (0-based)
         * @return BufferedImage of the frame
         */
        public BufferedImage getFrame(int frameIndex) {
            if (frameIndex < 0 || frameIndex >= frameCount) {
                frameIndex = 0;
            }

            BufferedImage frame = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
            int yOffset = frameIndex * frameHeight;

            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    // NativeImage stores ABGR, BufferedImage needs ARGB
                    int abgr = rawImage.getPixelRGBA(x, yOffset + y);
                    int argb = convertABGRtoARGB(abgr);
                    frame.setRGB(x, y, argb);
                }
            }

            return frame;
        }

        /**
         * Get the actual frame index for a given playback position.
         * This handles custom frame ordering from mcmeta.
         */
        public int getActualFrameIndex(int playbackFrame) {
            if (animationMetadata != null && animationMetadata.hasCustomFrameOrder()) {
                return animationMetadata.getFrameIndex(playbackFrame % animationMetadata.getTotalPlaybackFrames());
            }
            return playbackFrame % frameCount;
        }

        private int convertABGRtoARGB(int abgr) {
            int a = (abgr >> 24) & 0xFF;
            int b = (abgr >> 16) & 0xFF;
            int g = (abgr >> 8) & 0xFF;
            int r = abgr & 0xFF;
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        /**
         * Clean up native resources.
         */
        public void close() {
            if (rawImage != null) {
                rawImage.close();
            }
        }
    }

    /**
     * Extract all texture layers from an item's model.
     *
     * @param stack The item stack
     * @return List of texture layers used by the item
     */
    public List<TextureLayer> extractTextureLayers(ItemStack stack) {
        List<TextureLayer> layers = new ArrayList<>();

        if (stack.isEmpty()) {
            return layers;
        }

        try {
            // Get all sprites from the model
            Set<TextureAtlasSprite> sprites = getAllModelSprites(stack);

            for (TextureAtlasSprite sprite : sprites) {
                TextureLayer layer = extractLayerFromSprite(sprite, false);
                if (layer != null) {
                    layers.add(layer);
                }
            }

            // Also check for emissive variants
            for (TextureAtlasSprite sprite : new HashSet<>(sprites)) {
                ResourceLocation emissiveLoc = getEmissiveVariant(sprite.contents().name());
                if (emissiveLoc != null) {
                    TextureLayer emissiveLayer = extractLayerFromResourceLocation(emissiveLoc, true);
                    if (emissiveLayer != null) {
                        layers.add(emissiveLayer);
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Failed to extract texture layers for {}: {}", stack.getItem(), e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
        }

        return layers;
    }

    /**
     * Extract a texture layer from a specific texture path.
     * Useful for GTCEu overlay textures that aren't in the model quads.
     *
     * @param texturePath The texture path (e.g., "gtceu:block/overlay/machine/overlay_pipe_in")
     * @param isEmissive Whether this is an emissive layer
     * @return TextureLayer or null if not found
     */
    public TextureLayer extractLayerFromPath(String texturePath, boolean isEmissive) {
        ResourceLocation location = ResourceLocation.tryParse(texturePath);
        if (location == null) {
            return null;
        }
        return extractLayerFromResourceLocation(location, isEmissive);
    }

    /**
     * Extract a texture layer from a ResourceLocation.
     */
    public TextureLayer extractLayerFromResourceLocation(ResourceLocation location, boolean isEmissive) {
        try {
            // Build the actual texture file path
            ResourceLocation pngLocation = ResourceLocation.fromNamespaceAndPath(
                    location.getNamespace(),
                    "textures/" + location.getPath() + ".png"
            );
            ResourceLocation mcmetaLocation = ResourceLocation.fromNamespaceAndPath(
                    location.getNamespace(),
                    "textures/" + location.getPath() + ".png.mcmeta"
            );

            // Try to read the PNG file
            Optional<Resource> pngResource = resourceManager.getResource(pngLocation);
            if (pngResource.isEmpty()) {
                LOGGER.debug("RecipeFlow: Texture not found: {}", pngLocation);
                return null;
            }

            NativeImage image;
            try (InputStream is = pngResource.get().open()) {
                image = NativeImage.read(is);
            }

            // Try to read animation metadata
            McmetaParser.AnimationMetadata animMeta = null;
            Optional<Resource> mcmetaResource = resourceManager.getResource(mcmetaLocation);
            if (mcmetaResource.isPresent()) {
                try (InputStream is = mcmetaResource.get().open()) {
                    animMeta = McmetaParser.parse(is);
                    LOGGER.debug("RecipeFlow: Loaded animation metadata for {}: {} frames",
                            location, animMeta.frameCount());
                }
            }

            // Try to get the sprite from the texture atlas
            Function<ResourceLocation, TextureAtlasSprite> textureAtlas =
                    minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
            TextureAtlasSprite sprite = textureAtlas.apply(location);

            return new TextureLayer(location, image, animMeta, sprite, isEmissive);

        } catch (IOException e) {
            LOGGER.debug("RecipeFlow: Failed to extract texture layer {}: {}", location, e.getMessage());
            return null;
        }
    }

    /**
     * Extract a texture layer from a TextureAtlasSprite.
     */
    private TextureLayer extractLayerFromSprite(TextureAtlasSprite sprite, boolean isEmissive) {
        if (sprite == null) {
            return null;
        }

        try {
            ResourceLocation textureLoc = sprite.contents().name();

            // Get the original image from the sprite (already loaded in memory)
            NativeImage originalImage = sprite.contents().getOriginalImage();
            if (originalImage == null) {
                // Fall back to loading from resource
                return extractLayerFromResourceLocation(textureLoc, isEmissive);
            }

            // Read mcmeta for animation info
            ResourceLocation mcmetaLocation = ResourceLocation.fromNamespaceAndPath(
                    textureLoc.getNamespace(),
                    "textures/" + textureLoc.getPath() + ".png.mcmeta"
            );

            McmetaParser.AnimationMetadata animMeta = null;
            Optional<Resource> mcmetaResource = resourceManager.getResource(mcmetaLocation);
            if (mcmetaResource.isPresent()) {
                try (InputStream is = mcmetaResource.get().open()) {
                    animMeta = McmetaParser.parse(is);
                }
            }

            // Create a copy of the NativeImage since the original might be managed by MC
            NativeImage imageCopy = new NativeImage(originalImage.getWidth(), originalImage.getHeight(), false);
            imageCopy.copyFrom(originalImage);

            return new TextureLayer(textureLoc, imageCopy, animMeta, sprite, isEmissive);

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to extract layer from sprite {}: {}",
                    sprite.contents().name(), e.getMessage());
            return null;
        }
    }

    /**
     * Get all texture sprites from an item's model.
     */
    private Set<TextureAtlasSprite> getAllModelSprites(ItemStack stack) {
        Set<TextureAtlasSprite> sprites = new HashSet<>();

        try {
            BakedModel model = minecraft.getItemRenderer().getModel(stack, null, null, 0);

            // Add particle icon (use Forge's extended getParticleIcon with ModelData for mod compatibility)
            TextureAtlasSprite particleIcon = model.getParticleIcon(ModelData.EMPTY);
            if (particleIcon != null) {
                sprites.add(particleIcon);
            }

            // Get sprites from null-face quads (use Forge's extended getQuads with ModelData for mod compatibility)
            var random = net.minecraft.util.RandomSource.create();
            var nullQuads = model.getQuads(null, null, random, ModelData.EMPTY, null);
            for (var quad : nullQuads) {
                if (quad.getSprite() != null) {
                    sprites.add(quad.getSprite());
                }
            }

            // Get sprites from all directional quads
            for (Direction direction : Direction.values()) {
                var dirQuads = model.getQuads(null, direction, random, ModelData.EMPTY, null);
                for (var quad : dirQuads) {
                    if (quad.getSprite() != null) {
                        sprites.add(quad.getSprite());
                    }
                }
            }

            // For BlockItems, also check block model
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                var block = blockItem.getBlock();
                var defaultState = block.defaultBlockState();
                var blockModel = minecraft.getBlockRenderer().getBlockModel(defaultState);

                if (blockModel != null && blockModel != model) {
                    TextureAtlasSprite blockParticle = blockModel.getParticleIcon(ModelData.EMPTY);
                    if (blockParticle != null) {
                        sprites.add(blockParticle);
                    }

                    var blockRandom = net.minecraft.util.RandomSource.create();
                    var blockNullQuads = blockModel.getQuads(defaultState, null, blockRandom, ModelData.EMPTY, null);
                    for (var quad : blockNullQuads) {
                        if (quad.getSprite() != null) {
                            sprites.add(quad.getSprite());
                        }
                    }

                    for (Direction direction : Direction.values()) {
                        var dirQuads = blockModel.getQuads(defaultState, direction, blockRandom, ModelData.EMPTY, null);
                        for (var quad : dirQuads) {
                            if (quad.getSprite() != null) {
                                sprites.add(quad.getSprite());
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get model sprites: {}", e.getMessage());
        }

        return sprites;
    }

    /**
     * Get the emissive variant of a texture location.
     * GTCEu and other mods use _emissive suffix for glow layers.
     */
    private ResourceLocation getEmissiveVariant(ResourceLocation original) {
        String path = original.getPath();

        // Don't add _emissive if already present
        if (path.endsWith("_emissive")) {
            return null;
        }

        ResourceLocation emissiveLoc = ResourceLocation.fromNamespaceAndPath(
                original.getNamespace(),
                path + "_emissive"
        );

        // Check if the emissive texture exists
        ResourceLocation pngLocation = ResourceLocation.fromNamespaceAndPath(
                emissiveLoc.getNamespace(),
                "textures/" + emissiveLoc.getPath() + ".png"
        );

        if (resourceManager.getResource(pngLocation).isPresent()) {
            return emissiveLoc;
        }

        return null;
    }

    /**
     * Extract GTCEu-specific overlay layers based on machine type.
     *
     * @param machineId The GTCEu machine ID (e.g., "lv_input_bus")
     * @return List of overlay texture layers
     */
    public List<TextureLayer> extractGTCEuOverlayLayers(String machineId) {
        List<TextureLayer> layers = new ArrayList<>();
        String normalized = machineId.toLowerCase();

        // Determine overlay paths based on machine type
        List<String> overlayPaths = new ArrayList<>();

        if (normalized.contains("input") || normalized.contains("import")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_pipe_in");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_pipe_in_emissive");
        } else if (normalized.contains("output") || normalized.contains("export")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_pipe_out");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_pipe_out_emissive");
        } else if (normalized.contains("buffer")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_buffer");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_buffer_emissive");
        } else if (normalized.contains("quantum_chest") || normalized.contains("qchest")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_qchest");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_qchest_emissive");
        } else if (normalized.contains("quantum_tank") || normalized.contains("qtank")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_qtank");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_qtank_emissive");
        } else if (normalized.contains("creative")) {
            overlayPaths.add("gtceu:block/overlay/machine/overlay_creativecontainer");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_creativecontainer_emissive");
        } else {
            // Default: screen overlay for controllers and other machines
            overlayPaths.add("gtceu:block/overlay/machine/overlay_screen");
            overlayPaths.add("gtceu:block/overlay/machine/overlay_screen_emissive");
        }

        // Try active variants
        List<String> activePaths = new ArrayList<>();
        for (String path : overlayPaths) {
            if (!path.contains("_active")) {
                String basePath = path.replace("_emissive", "");
                activePaths.add(basePath + "_active");
                activePaths.add(basePath + "_active_emissive");
            }
        }
        overlayPaths.addAll(activePaths);

        // Extract layers
        for (String path : overlayPaths) {
            boolean isEmissive = path.contains("_emissive");
            TextureLayer layer = extractLayerFromPath(path, isEmissive);
            if (layer != null && layer.isAnimated()) {
                layers.add(layer);
                LOGGER.debug("RecipeFlow: Found GTCEu overlay layer: {} ({} frames)",
                        path, layer.getFrameCount());
            }
        }

        return layers;
    }
}

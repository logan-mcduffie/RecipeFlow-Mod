package com.recipeflow.mod.v120plus.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders fluid icons as images, supporting both static and animated fluid textures.
 *
 * Fluids in Minecraft are rendered using:
 * 1. A base texture sprite (still or flowing)
 * 2. A tint color applied to the texture
 *
 * This class extracts the fluid's sprite, applies the tint color, and handles
 * animated fluid textures (like the bubbling effect seen in JEI).
 */
@OnlyIn(Dist.CLIENT)
public class FluidIconRenderer {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int DEFAULT_ICON_SIZE = 128;

    private final Minecraft minecraft;

    public FluidIconRenderer() {
        this.minecraft = Minecraft.getInstance();
    }

    /**
     * Check if a fluid has an animated texture.
     *
     * @param fluid The fluid to check
     * @return true if the fluid's texture is animated
     */
    public boolean isAnimated(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }

        try {
            TextureAtlasSprite sprite = getFluidSprite(fluid);
            if (sprite != null) {
                long frameCount = sprite.contents().getUniqueFrames().count();
                return frameCount > 1;
            }
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to check fluid animation for {}: {}",
                    ForgeRegistries.FLUIDS.getKey(fluid), e.getMessage());
        }
        return false;
    }

    /**
     * Get the fluid's still texture sprite.
     *
     * @param fluid The fluid
     * @return The texture sprite, or null if not found
     */
    @Nullable
    public TextureAtlasSprite getFluidSprite(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return null;
        }

        try {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation stillTexture = extensions.getStillTexture();

            if (stillTexture == null) {
                LOGGER.debug("RecipeFlow: No still texture for fluid {}",
                        ForgeRegistries.FLUIDS.getKey(fluid));
                return null;
            }

            TextureAtlas blockAtlas = minecraft.getModelManager()
                    .getAtlas(InventoryMenu.BLOCK_ATLAS);
            return blockAtlas.getSprite(stillTexture);
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get sprite for fluid {}: {}",
                    ForgeRegistries.FLUIDS.getKey(fluid), e.getMessage());
            return null;
        }
    }

    /**
     * Get the tint color for a fluid.
     *
     * @param fluid The fluid
     * @return The tint color as ARGB (0xAARRGGBB), or 0xFFFFFFFF (white/no tint) if not available
     */
    public int getFluidTintColor(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return 0xFFFFFFFF;
        }

        try {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
            // getTintColor returns RGB, we add full alpha
            int rgb = extensions.getTintColor();
            // If the color already has alpha, use it as-is; otherwise add full opacity
            if ((rgb & 0xFF000000) == 0) {
                return 0xFF000000 | rgb;
            }
            return rgb;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get tint color for fluid {}: {}",
                    ForgeRegistries.FLUIDS.getKey(fluid), e.getMessage());
            return 0xFFFFFFFF;
        }
    }

    /**
     * Render a single frame of a fluid icon.
     *
     * @param fluid The fluid to render
     * @return BufferedImage of the rendered fluid icon
     */
    public BufferedImage renderFluid(Fluid fluid) {
        return renderFluid(fluid, DEFAULT_ICON_SIZE);
    }

    /**
     * Render a single frame of a fluid icon at a specific size.
     *
     * @param fluid The fluid to render
     * @param size The desired icon size
     * @return BufferedImage of the rendered fluid icon
     */
    public BufferedImage renderFluid(Fluid fluid, int size) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return createEmptyImage(size);
        }

        TextureAtlasSprite sprite = getFluidSprite(fluid);
        if (sprite == null) {
            LOGGER.warn("RecipeFlow: No sprite found for fluid {}",
                    ForgeRegistries.FLUIDS.getKey(fluid));
            return createEmptyImage(size);
        }

        int tintColor = getFluidTintColor(fluid);

        // Extract frame 0 from the sprite
        BufferedImage frame = extractSpriteFrame(sprite, 0);
        if (frame == null) {
            return createEmptyImage(size);
        }

        // Apply tint color
        BufferedImage tinted = applyTint(frame, tintColor);

        // Scale to desired size
        return DirectFrameExtractor.scale(tinted, size, size);
    }

    /**
     * Render all animation frames of a fluid icon.
     *
     * @param fluid The fluid to render
     * @return AnimationSequence containing all frames with timing
     */
    public AnimationSequence renderFluidAnimation(Fluid fluid) {
        return renderFluidAnimation(fluid, DEFAULT_ICON_SIZE);
    }

    /**
     * Render all animation frames of a fluid icon at a specific size.
     *
     * @param fluid The fluid to render
     * @param size The desired icon size
     * @return AnimationSequence containing all frames with timing
     */
    public AnimationSequence renderFluidAnimation(Fluid fluid, int size) {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        if (fluid == null || fluid == Fluids.EMPTY) {
            frames.add(createEmptyImage(size));
            durations.add(100);
            return new AnimationSequence(frames, durations);
        }

        TextureAtlasSprite sprite = getFluidSprite(fluid);
        if (sprite == null) {
            LOGGER.warn("RecipeFlow: No sprite found for fluid animation {}",
                    ForgeRegistries.FLUIDS.getKey(fluid));
            frames.add(createEmptyImage(size));
            durations.add(100);
            return new AnimationSequence(frames, durations);
        }

        int tintColor = getFluidTintColor(fluid);

        // Get animation timing info
        SpriteAnimationMetadata.FrameTimingInfo timing =
                SpriteAnimationMetadata.getFrameTimings(sprite);

        if (!timing.isAnimated()) {
            // Single frame fluid
            BufferedImage frame = extractSpriteFrame(sprite, 0);
            if (frame != null) {
                BufferedImage tinted = applyTint(frame, tintColor);
                frames.add(DirectFrameExtractor.scale(tinted, size, size));
            } else {
                frames.add(createEmptyImage(size));
            }
            durations.add(0);
            return new AnimationSequence(frames, durations);
        }

        // Extract all animation frames
        List<Integer> frameIndices = timing.frameIndices();
        List<Integer> frameDurations = timing.frameDurationsMs();

        LOGGER.debug("RecipeFlow: Extracting {} frames for fluid {}",
                timing.frameCount(), ForgeRegistries.FLUIDS.getKey(fluid));

        for (int i = 0; i < timing.frameCount(); i++) {
            int frameIndex = frameIndices.get(i);
            int duration = frameDurations.get(i);

            BufferedImage frame = extractSpriteFrame(sprite, frameIndex);
            if (frame != null) {
                BufferedImage tinted = applyTint(frame, tintColor);
                frames.add(DirectFrameExtractor.scale(tinted, size, size));
                durations.add(duration);
            }
        }

        if (frames.isEmpty()) {
            // Fallback
            frames.add(createEmptyImage(size));
            durations.add(100);
        }

        LOGGER.debug("RecipeFlow: Created {} frames for fluid {} animation",
                frames.size(), ForgeRegistries.FLUIDS.getKey(fluid));

        return new AnimationSequence(frames, durations);
    }

    /**
     * Extract a specific frame from a sprite.
     *
     * @param sprite The sprite to extract from
     * @param frameIndex The frame index (0-based)
     * @return The extracted frame as BufferedImage, or null on failure
     */
    @Nullable
    private BufferedImage extractSpriteFrame(TextureAtlasSprite sprite, int frameIndex) {
        try {
            NativeImage sourceImage = ObfuscationHelper.getOriginalImage(sprite.contents());
            if (sourceImage == null) {
                LOGGER.warn("RecipeFlow: Could not get source image for sprite {}",
                        sprite.contents().name());
                return null;
            }

            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();
            int sourceWidth = sourceImage.getWidth();
            int sourceHeight = sourceImage.getHeight();
            int yOffset = frameIndex * spriteHeight;

            // Log image dimensions and sample pixels for debugging
            if (frameIndex == 0) {
                LOGGER.info("RecipeFlow: Extracting from {} - sprite {}x{}, source {}x{}",
                        sprite.contents().name(), spriteWidth, spriteHeight, sourceWidth, sourceHeight);
                try {
                    int sample1 = sourceImage.getPixelRGBA(0, 0);
                    int sample2 = sourceImage.getPixelRGBA(spriteWidth / 2, spriteHeight / 2);
                    LOGGER.info("RecipeFlow: Sample pixels - (0,0)=0x{}, (center)=0x{}",
                            Integer.toHexString(sample1), Integer.toHexString(sample2));
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow: Failed to sample pixels: {}", e.getMessage());
                }
            }

            if (yOffset + spriteHeight > sourceHeight) {
                LOGGER.warn("RecipeFlow: Frame {} out of bounds for sprite {}",
                        frameIndex, sprite.contents().name());
                // Fall back to frame 0
                yOffset = 0;
            }

            return DirectFrameExtractor.extractRegion(sourceImage, 0, yOffset, spriteWidth, spriteHeight);
        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Failed to extract sprite frame: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Apply a tint color to an image.
     * The tint is multiplied with each pixel's color channels.
     *
     * @param image The source image
     * @param tintColor The tint color as ARGB (0xAARRGGBB)
     * @return New image with tint applied
     */
    private BufferedImage applyTint(BufferedImage image, int tintColor) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // Extract tint RGB components (ignore tint alpha, use source alpha)
        int tintR = (tintColor >> 16) & 0xFF;
        int tintG = (tintColor >> 8) & 0xFF;
        int tintB = tintColor & 0xFF;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);

                int a = (pixel >> 24) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // Multiply color channels by tint (normalized)
                r = (r * tintR) / 255;
                g = (g * tintG) / 255;
                b = (b * tintB) / 255;

                int tinted = (a << 24) | (r << 16) | (g << 8) | b;
                result.setRGB(x, y, tinted);
            }
        }

        return result;
    }

    /**
     * Create an empty (transparent) image.
     */
    private BufferedImage createEmptyImage(int size) {
        return new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Render a fluid from a FluidStack.
     */
    public BufferedImage renderFluidStack(FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.isEmpty()) {
            return createEmptyImage(DEFAULT_ICON_SIZE);
        }
        return renderFluid(fluidStack.getFluid());
    }

    /**
     * Render animated fluid from a FluidStack.
     */
    public AnimationSequence renderFluidStackAnimation(FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.isEmpty()) {
            List<BufferedImage> frames = new ArrayList<>();
            frames.add(createEmptyImage(DEFAULT_ICON_SIZE));
            return new AnimationSequence(frames, List.of(100));
        }
        return renderFluidAnimation(fluidStack.getFluid());
    }
}

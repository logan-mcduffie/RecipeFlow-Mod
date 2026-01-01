package com.recipeflow.mod.v120plus.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts individual animation frames directly from sprite source images.
 *
 * This bypasses the texture atlas animation system entirely, allowing us to
 * get each frame's pixel data regardless of what GTCEu or other mods do
 * with their rendering pipeline.
 *
 * Animation frames in Minecraft are stored as vertical strips in the source PNG.
 * For a 16x16 sprite with 4 frames, the PNG is 16x64 (16 pixels wide, 64 pixels tall).
 */
@OnlyIn(Dist.CLIENT)
public class DirectFrameExtractor {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Result of frame extraction containing all frames and timing info.
     */
    public record ExtractionResult(
            List<BufferedImage> frames,
            List<Integer> frameDurationsMs,
            int spriteWidth,
            int spriteHeight,
            boolean success,
            String errorMessage
    ) {
        public static ExtractionResult failure(String message) {
            return new ExtractionResult(List.of(), List.of(), 0, 0, false, message);
        }

        public static ExtractionResult success(List<BufferedImage> frames, List<Integer> durationsMs,
                                                int width, int height) {
            return new ExtractionResult(frames, durationsMs, width, height, true, null);
        }

        public int frameCount() {
            return frames.size();
        }

        public int totalDurationMs() {
            return frameDurationsMs.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Extract all animation frames from a TextureAtlasSprite.
     *
     * @param sprite The sprite to extract frames from
     * @return ExtractionResult containing all frames and timing, or failure info
     */
    public static ExtractionResult extractFrames(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return ExtractionResult.failure("Sprite is null");
        }

        SpriteContents contents = sprite.contents();
        String spriteName = contents.name().toString();

        LOGGER.info("RecipeFlow DirectFrameExtractor: Extracting frames from {}", spriteName);

        // Get frame timing info
        SpriteAnimationMetadata.FrameTimingInfo timingInfo =
                SpriteAnimationMetadata.getFrameTimings(sprite);

        if (!timingInfo.isAnimated()) {
            LOGGER.info("RecipeFlow DirectFrameExtractor: {} is not animated (1 frame)", spriteName);
            // Return single frame for non-animated sprites
            BufferedImage singleFrame = extractSingleFrame(contents, 0);
            if (singleFrame == null) {
                return ExtractionResult.failure("Failed to extract single frame");
            }
            return ExtractionResult.success(
                    List.of(singleFrame),
                    List.of(0),
                    contents.width(),
                    contents.height()
            );
        }

        // Get the source NativeImage containing all frames
        NativeImage sourceImage = ObfuscationHelper.getOriginalImage(contents);
        if (sourceImage == null) {
            LOGGER.warn("RecipeFlow DirectFrameExtractor: Could not get source image for {}, " +
                    "trying alternative extraction", spriteName);
            return extractFramesAlternative(sprite, timingInfo);
        }

        int spriteWidth = contents.width();
        int spriteHeight = contents.height();
        int sourceHeight = sourceImage.getHeight();
        int sourceWidth = sourceImage.getWidth();

        LOGGER.info("RecipeFlow DirectFrameExtractor: {} - sprite {}x{}, source {}x{}, {} frames",
                spriteName, spriteWidth, spriteHeight, sourceWidth, sourceHeight, timingInfo.frameCount());

        // Sample a few pixels to check if the image has actual data
        try {
            int sample1 = sourceImage.getPixelRGBA(0, 0);
            int sample2 = sourceImage.getPixelRGBA(spriteWidth / 2, spriteHeight / 2);
            LOGGER.info("RecipeFlow DirectFrameExtractor: {} - sample pixels: (0,0)=0x{}, (center)=0x{}",
                    spriteName, Integer.toHexString(sample1), Integer.toHexString(sample2));
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow DirectFrameExtractor: {} - failed to sample pixels: {}",
                    spriteName, e.getMessage());
        }

        // Extract each frame
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> frameIndices = timingInfo.frameIndices();
        List<Integer> frameDurations = timingInfo.frameDurationsMs();

        for (int i = 0; i < timingInfo.frameCount(); i++) {
            int frameIndex = frameIndices.get(i);
            int yOffset = frameIndex * spriteHeight;

            if (yOffset + spriteHeight > sourceHeight) {
                LOGGER.warn("RecipeFlow DirectFrameExtractor: Frame {} index {} is out of bounds " +
                        "(yOffset={}, spriteHeight={}, sourceHeight={})",
                        i, frameIndex, yOffset, spriteHeight, sourceHeight);
                continue;
            }

            BufferedImage frame = extractRegion(sourceImage, 0, yOffset, spriteWidth, spriteHeight);
            if (frame != null) {
                frames.add(frame);
                LOGGER.debug("RecipeFlow DirectFrameExtractor: Extracted frame {} (index {}) from y={}",
                        i, frameIndex, yOffset);
            } else {
                LOGGER.warn("RecipeFlow DirectFrameExtractor: Failed to extract frame {} from {}",
                        i, spriteName);
            }
        }

        if (frames.isEmpty()) {
            return ExtractionResult.failure("No frames could be extracted");
        }

        LOGGER.info("RecipeFlow DirectFrameExtractor: Successfully extracted {} frames from {}",
                frames.size(), spriteName);

        return ExtractionResult.success(frames, frameDurations, spriteWidth, spriteHeight);
    }

    /**
     * Alternative extraction method when direct NativeImage access fails.
     * Uses the sprite's UV coordinates to extract from the texture atlas.
     */
    private static ExtractionResult extractFramesAlternative(TextureAtlasSprite sprite,
                                                              SpriteAnimationMetadata.FrameTimingInfo timingInfo) {
        LOGGER.info("RecipeFlow DirectFrameExtractor: Using alternative extraction for {}",
                sprite.contents().name());

        // For now, return a failure - we'll implement atlas-based extraction if needed
        // This would involve reading directly from the GPU texture, which is more complex
        return ExtractionResult.failure("Alternative extraction not yet implemented - " +
                "source NativeImage not accessible");
    }

    /**
     * Extract a single frame from SpriteContents at the given frame index.
     */
    @Nullable
    private static BufferedImage extractSingleFrame(SpriteContents contents, int frameIndex) {
        NativeImage sourceImage = ObfuscationHelper.getOriginalImage(contents);
        if (sourceImage == null) {
            LOGGER.warn("RecipeFlow DirectFrameExtractor: Could not get source image");
            return null;
        }

        int spriteWidth = contents.width();
        int spriteHeight = contents.height();
        int yOffset = frameIndex * spriteHeight;

        return extractRegion(sourceImage, 0, yOffset, spriteWidth, spriteHeight);
    }

    /**
     * Extract a rectangular region from a NativeImage and convert to BufferedImage.
     *
     * @param source The source NativeImage
     * @param x Starting X coordinate
     * @param y Starting Y coordinate
     * @param width Width of region to extract
     * @param height Height of region to extract
     * @return BufferedImage with the extracted pixels, or null on failure
     */
    @Nullable
    public static BufferedImage extractRegion(NativeImage source, int x, int y, int width, int height) {
        try {
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

            // Check if the NativeImage appears to be valid/not closed
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            boolean allZero = true;

            for (int py = 0; py < height; py++) {
                for (int px = 0; px < width; px++) {
                    int srcX = x + px;
                    int srcY = y + py;

                    if (srcX >= sourceWidth || srcY >= sourceHeight) {
                        continue;
                    }

                    // NativeImage.getPixelRGBA returns ABGR format (despite the name)
                    // Layout: 0xAABBGGRR
                    int abgr = source.getPixelRGBA(srcX, srcY);

                    if (abgr != 0) {
                        allZero = false;
                    }

                    // Convert ABGR to ARGB for BufferedImage
                    int a = (abgr >> 24) & 0xFF;
                    int b = (abgr >> 16) & 0xFF;
                    int g = (abgr >> 8) & 0xFF;
                    int r = abgr & 0xFF;
                    int argb = (a << 24) | (r << 16) | (g << 8) | b;

                    result.setRGB(px, py, argb);
                }
            }

            if (allZero) {
                LOGGER.warn("RecipeFlow DirectFrameExtractor: All pixels are zero - NativeImage may be closed or empty");
            }

            return result;
        } catch (Exception e) {
            LOGGER.error("RecipeFlow DirectFrameExtractor: Failed to extract region: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Convert a NativeImage to BufferedImage.
     */
    @Nullable
    public static BufferedImage nativeImageToBufferedImage(NativeImage nativeImage) {
        if (nativeImage == null) return null;
        return extractRegion(nativeImage, 0, 0, nativeImage.getWidth(), nativeImage.getHeight());
    }

    /**
     * Composite an overlay frame onto a base image.
     * The overlay is drawn on top of the base using alpha blending.
     *
     * @param base The base image to composite onto
     * @param overlay The overlay image (typically an emissive texture frame)
     * @param overlayX X position to place overlay on base
     * @param overlayY Y position to place overlay on base
     * @return New BufferedImage with composite result
     */
    public static BufferedImage composite(BufferedImage base, BufferedImage overlay,
                                          int overlayX, int overlayY) {
        // Create a copy of the base
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(),
                BufferedImage.TYPE_INT_ARGB);

        // Draw base
        java.awt.Graphics2D g = result.createGraphics();
        g.drawImage(base, 0, 0, null);

        // Draw overlay with alpha blending
        g.setComposite(java.awt.AlphaComposite.SrcOver);
        g.drawImage(overlay, overlayX, overlayY, null);

        g.dispose();
        return result;
    }

    /**
     * Scale a BufferedImage to a new size.
     */
    public static BufferedImage scale(BufferedImage source, int newWidth, int newHeight) {
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }
}

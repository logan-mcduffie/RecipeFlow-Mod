package com.recipeflow.mod.v120plus.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single frame in an animation sequence with its timing.
 */
record AnimationFrame(int frameIndex, int durationMs) {}

/**
 * Represents a complete animation sequence with frames and their individual timings.
 */
record AnimationSequence(List<BufferedImage> frames, List<Integer> frameDurationsMs) {
    public int totalFrames() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }
}

/**
 * Client-side renderer for extracting item icons as images.
 * Supports both static and animated textures.
 */
@OnlyIn(Dist.CLIENT)
public class AnimatedIconRenderer {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int ICON_SIZE_DEFAULT = 128;  // Items render at 128px
    private static final int ICON_SIZE_3D = 256;  // Blocks and 3D items render at 256px
    private static final int MAX_ATLAS_SIZE = 4096;  // Maximum texture atlas size

    /**
     * Multiplier applied to frame times to match in-game animation speed.
     * Set to 1 for accurate timing now that we read per-frame durations from mcmeta.
     * Increase if GIFs play too fast in browsers (some browsers have minimum frame delays).
     */

    private final Minecraft minecraft;
    private RenderTarget renderTarget;
    private int currentRenderTargetSize = 0;

    // Lazy-loaded GTCEu detection
    private static Boolean gtceuLoaded = null;

    public AnimatedIconRenderer() {
        this.minecraft = Minecraft.getInstance();
    }

    /**
     * Check if an item has an animated texture.
     * Checks ALL sprites from all model quads, not just the particle icon.
     * This is important for modded blocks that use overlay textures for animations.
     *
     * @param stack The item stack to check
     * @return true if the item's texture is animated
     */
    public boolean isAnimated(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        try {
            // Check all sprites from the model, not just the particle icon
            List<TextureAtlasSprite> sprites = getAllModelSprites(stack);
            for (TextureAtlasSprite sprite : sprites) {
                if (sprite != null) {
                    long frameCount = sprite.contents().getUniqueFrames().count();
                    if (frameCount > 1) {
                        LOGGER.debug("RecipeFlow: Detected animated texture for {} with {} frames (sprite: {})",
                                stack.getItem(), frameCount, sprite.contents().name());
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to check animation for {}: {}", stack.getItem(), e.getMessage());
            return false;
        }
    }

    /**
     * Get the number of animation frames for an item.
     * Checks all model sprites and returns the highest frame count found.
     *
     * @param stack The item stack
     * @return Number of frames (1 for static items)
     */
    public int getFrameCount(ItemStack stack) {
        if (stack.isEmpty()) {
            return 1;
        }

        try {
            TextureAtlasSprite sprite = getAnimatedSprite(stack);
            if (sprite != null) {
                SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
                return timing.frameCount();
            }
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get frame count for {}: {}", stack.getItem(), e.getMessage());
        }
        return 1;
    }

    /**
     * Get the frame time in milliseconds for animated textures.
     * Reads the actual frame time from the sprite's animation metadata.
     * Returns the average frame time if frames have variable durations.
     *
     * @param stack The item stack
     * @return Frame time in ms, or 0 for static items
     */
    public int getFrameTimeMs(ItemStack stack) {
        if (!isAnimated(stack)) {
            return 0;
        }

        try {
            TextureAtlasSprite sprite = getAnimatedSprite(stack);
            if (sprite != null) {
                SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
                if (timing.isAnimated()) {
                    int avgFrameTime = timing.averageFrameDurationMs();
                    LOGGER.debug("RecipeFlow: Animation frame time for {}: {}ms avg ({} frames, {}ms total)",
                            stack.getItem(), avgFrameTime, timing.frameCount(), timing.totalDurationMs());
                    return avgFrameTime;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get frame time for {}: {}", stack.getItem(), e.getMessage());
        }

        // Default fallback: 100ms (2 ticks)
        return 100;
    }

    /**
     * Get detailed frame timing information for an animated sprite.
     * This provides per-frame durations, which is more accurate than the average.
     *
     * @param sprite The sprite to get timing for
     * @return FrameTimingInfo with per-frame data, or null if not animated
     */
    public SpriteAnimationMetadata.FrameTimingInfo getDetailedFrameTimings(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return null;
        }
        SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
        return timing.isAnimated() ? timing : null;
    }

    /**
     * Get the full animation sequence with per-frame timing.
     * This reads the frames array from the .mcmeta animation data, which can specify:
     * - Custom frame order (frames can repeat, play in any order)
     * - Per-frame timing (each frame can have its own duration)
     *
     * @param stack The item stack
     * @return List of AnimationFrame with frame indices and durations, or null if not animated
     */
    public List<AnimationFrame> getAnimationSequence(ItemStack stack) {
        if (!isAnimated(stack)) {
            return null;
        }

        try {
            TextureAtlasSprite sprite = getAnimatedSprite(stack);
            if (sprite == null) {
                return null;
            }

            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            if (!timing.isAnimated()) {
                return null;
            }

            // Convert FrameTimingInfo to List<AnimationFrame>
            List<AnimationFrame> sequence = new ArrayList<>();
            List<Integer> indices = timing.frameIndices();
            List<Integer> durations = timing.frameDurationsMs();

            for (int i = 0; i < timing.frameCount(); i++) {
                sequence.add(new AnimationFrame(indices.get(i), durations.get(i)));
            }

            LOGGER.debug("RecipeFlow: Animation sequence for {}: {} frames, total {}ms, interpolated={}",
                    stack.getItem(), timing.frameCount(), timing.totalDurationMs(), timing.interpolated());

            return sequence;

        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to get animation sequence for {}: {}", stack.getItem(), e.getMessage());
            return null;
        }
    }

    /**
     * Get the appropriate icon size for an item based on its model type.
     */
    private int getIconSizeForItem(ItemStack stack) {
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, null, null, 0);
        // Use higher resolution for 3D models (blocks, tools, etc.)
        return model.isGui3d() ? ICON_SIZE_3D : ICON_SIZE_DEFAULT;
    }

    /**
     * Render a single frame of an item to a BufferedImage.
     * Renders directly at target size (128px for items, 256px for blocks).
     * Anti-aliasing can be applied on the web app side when displaying at smaller sizes.
     *
     * @param stack The item stack to render
     * @return BufferedImage of the rendered item
     */
    public BufferedImage renderItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return createEmptyImage(ICON_SIZE_DEFAULT);
        }

        int iconSize = getIconSizeForItem(stack);
        ensureRenderTarget(iconSize);

        try {
            // Bind our render target
            renderTarget.bindWrite(true);

            // Clear the buffer
            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            // Enable depth testing for proper 3D rendering
            RenderSystem.enableDepthTest();

            // Set up orthographic projection matching Minecraft's GUI rendering
            // Use large depth range to avoid clipping rotated 3D models
            Matrix4f projectionMatrix = new Matrix4f().ortho(
                    0.0f, iconSize, 0.0f, iconSize, -1000.0f, 1000.0f
            );
            RenderSystem.setProjectionMatrix(projectionMatrix, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);

            // Render the item at target size
            renderItemInternal(stack, iconSize);

            // Read pixels from the framebuffer
            BufferedImage image = readPixels(iconSize);

            // Unbind render target
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

            return image;

        } catch (Exception e) {
            // Log the error for debugging - this often happens when called from wrong thread
            LOGGER.warn("RecipeFlow: Failed to render item icon: {}", e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
            return createEmptyImage(iconSize);
        }
    }

    /**
     * Render animation frames in the correct playback sequence with per-frame timing.
     * This method respects the animation metadata from .mcmeta files, including:
     * - Custom frame order (frames can repeat or play in any sequence)
     * - Per-frame timing (each frame can have a different duration)
     *
     * @param stack The item stack to render
     * @return AnimationSequence containing frames in playback order with their durations
     */
    public AnimationSequence renderAnimationSequence(ItemStack stack) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty()) {
            sequenceFrames.add(createEmptyImage(ICON_SIZE_DEFAULT));
            frameDurations.add(100);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Get the animation sequence (frame indices and timings)
        List<AnimationFrame> animationSequence = getAnimationSequence(stack);

        if (animationSequence == null || animationSequence.isEmpty()) {
            // Not animated, return single frame
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Extract all unique frames from the sprite
        List<BufferedImage> uniqueFrames = new ArrayList<>();
        try {
            TextureAtlasSprite sprite = getAnimatedSprite(stack);
            if (sprite != null) {
                int uniqueFrameCount = (int) sprite.contents().getUniqueFrames().count();
                uniqueFrames = extractSpriteFrames(sprite, uniqueFrameCount);
            }
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to extract sprite frames for {}: {}", stack.getItem(), e.getMessage());
        }

        if (uniqueFrames.isEmpty()) {
            // Fallback to single rendered frame
            LOGGER.warn("RecipeFlow: Could not extract frames, falling back to single frame for {}", stack.getItem());
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(getFrameTimeMs(stack));
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Build the sequence in playback order
        for (AnimationFrame frame : animationSequence) {
            int frameIndex = frame.frameIndex();
            int duration = frame.durationMs();

            if (frameIndex >= 0 && frameIndex < uniqueFrames.size()) {
                sequenceFrames.add(uniqueFrames.get(frameIndex));
                frameDurations.add(duration);
            } else {
                LOGGER.warn("RecipeFlow: Frame index {} out of bounds (max {}), skipping",
                        frameIndex, uniqueFrames.size() - 1);
            }
        }

        if (sequenceFrames.isEmpty()) {
            // Should not happen, but safety fallback
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(getFrameTimeMs(stack));
        }

        LOGGER.debug("RecipeFlow: Built animation sequence for {} with {} frames (unique: {})",
                stack.getItem(), sequenceFrames.size(), uniqueFrames.size());

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Render animation frames by manipulating the texture atlas directly.
     * This uploads each animation frame to the GPU atlas for ALL animated sprites,
     * then renders the full block, capturing the complete composited result
     * (hull + overlays + animated textures).
     *
     * This is the correct approach for blocks like GTCEu machines where multiple
     * animated overlays are composited with other textures during rendering.
     *
     * @param stack The item stack to render
     * @return AnimationSequence with full block renders at each animation frame
     */
    public AnimationSequence renderAnimationSequenceFullBlock(ItemStack stack) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty()) {
            sequenceFrames.add(createEmptyImage(ICON_SIZE_DEFAULT));
            frameDurations.add(100);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Get ALL animated sprites (not just the one with most frames)
        List<TextureAtlasSprite> animatedSprites = getAllAnimatedSprites(stack);
        if (animatedSprites.isEmpty()) {
            // Not animated, return single frame
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        LOGGER.info("RecipeFlow: Found {} animated sprites for {}", animatedSprites.size(), stack.getItem());

        // Get animation sequence info from the sprite with most frames (for timing)
        List<AnimationFrame> animationSequence = getAnimationSequence(stack);
        if (animationSequence == null || animationSequence.isEmpty()) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        LOGGER.info("RecipeFlow: Rendering full block animation for {} with {} frames, {} animated sprites",
                stack.getItem(), animationSequence.size(), animatedSprites.size());

        // Prepare animation info for all sprites
        List<SpriteAnimationInfo> spriteInfos = new ArrayList<>();

        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationInfo info = prepareSpritAnimationInfo(sprite);
            if (info != null) {
                spriteInfos.add(info);
                LOGGER.debug("RecipeFlow: Prepared animation info for sprite {} at ({}, {})",
                        sprite.contents().name(), info.atlasX, info.atlasY);
            }
        }

        if (spriteInfos.isEmpty()) {
            LOGGER.warn("RecipeFlow: Could not prepare any sprite animation info for {}, falling back to sprite extraction",
                    stack.getItem());
            return renderAnimationSequence(stack);
        }

        try {
            // Get the texture atlas
            var textureManager = minecraft.getTextureManager();
            var blockAtlas = textureManager.getTexture(InventoryMenu.BLOCK_ATLAS);

            if (blockAtlas == null) {
                LOGGER.warn("RecipeFlow: Could not get block atlas texture");
                return renderAnimationSequence(stack);
            }

            // For each frame in the animation sequence, upload it for ALL sprites and render
            for (AnimationFrame frame : animationSequence) {
                int frameIndex = frame.frameIndex();
                int duration = frame.durationMs();

                try {
                    // Bind the block atlas texture before uploading frames
                    RenderSystem.bindTexture(blockAtlas.getId());

                    // Upload this frame to ALL animated sprites
                    for (SpriteAnimationInfo info : spriteInfos) {
                        // Calculate the frame index for this sprite (wrap around if needed)
                        int spriteFrameIndex = frameIndex % info.frameCount;
                        info.uploadFrameMethod.invoke(info.animatedTexture, info.atlasX, info.atlasY, spriteFrameIndex);
                    }

                    // Now render the full item - the atlas now has this frame's textures for all sprites
                    BufferedImage renderedFrame = renderItem(stack);

                    sequenceFrames.add(renderedFrame);
                    frameDurations.add(duration);

                    LOGGER.debug("RecipeFlow: Rendered frame {} for {} (updated {} sprites)",
                            frameIndex, stack.getItem(), spriteInfos.size());
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow: Failed to render frame {} for {}: {}",
                            frameIndex, stack.getItem(), e.getMessage());
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("RecipeFlow: Frame upload exception:", e);
                    }
                }
            }

            // Restore frame 0 for all sprites to leave atlas in a clean state
            try {
                RenderSystem.bindTexture(blockAtlas.getId());
                for (SpriteAnimationInfo info : spriteInfos) {
                    info.uploadFrameMethod.invoke(info.animatedTexture, info.atlasX, info.atlasY, 0);
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Error in full block animation rendering for {}: {}",
                    stack.getItem(), e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
            // Fall back to old method
            return renderAnimationSequence(stack);
        }

        if (sequenceFrames.isEmpty()) {
            // Safety fallback
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(getFrameTimeMs(stack));
        }

        LOGGER.info("RecipeFlow: Completed full block animation for {} with {} frames",
                stack.getItem(), sequenceFrames.size());

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * UNIVERSAL animation rendering using Minecraft's global texture atlas tick system.
     * This works for ANY mod because it ticks ALL animated textures in the atlas simultaneously,
     * just like the game itself does. No need to know which specific sprites are used.
     *
     * This is the recommended method for mods like GTCEu that use custom renderers
     * and don't expose their texture references through standard model quads.
     *
     * @param stack The item stack to render
     * @param frameCount Number of unique animation frames to capture
     * @param frameTimeMs Time per frame in milliseconds
     * @return AnimationSequence with rendered frames
     */
    public AnimationSequence renderAnimationSequenceUniversal(ItemStack stack, int frameCount, int frameTimeMs) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty() || frameCount <= 1) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        LOGGER.info("RecipeFlow: Starting universal animation capture for {} with {} frames",
                stack.getItem(), frameCount);

        try {
            // Get the block texture atlas
            var textureManager = minecraft.getTextureManager();
            var blockAtlasTexture = textureManager.getTexture(InventoryMenu.BLOCK_ATLAS);

            if (blockAtlasTexture == null) {
                LOGGER.warn("RecipeFlow: Could not get block atlas texture");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(0);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            // Get the TextureAtlas object (which has the animatedTextures list and tick method)
            // The texture from getTexture is an AbstractTexture, but for BLOCK_ATLAS it's a TextureAtlas
            Object atlas = blockAtlasTexture;

            // Find the animatedTextures field (list of Tickable animated textures)
            Field animatedTexturesField = null;
            String[] possibleFieldNames = {"animatedTextures", "f_118262_"};

            for (String fieldName : possibleFieldNames) {
                try {
                    animatedTexturesField = atlas.getClass().getDeclaredField(fieldName);
                    animatedTexturesField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (animatedTexturesField == null) {
                LOGGER.warn("RecipeFlow: Could not find animatedTextures field on TextureAtlas");
                // Fall back to the sprite-specific method
                return renderAnimationSequenceFullBlock(stack);
            }

            @SuppressWarnings("unchecked")
            List<Object> animatedTextures = (List<Object>) animatedTexturesField.get(atlas);

            if (animatedTextures == null || animatedTextures.isEmpty()) {
                LOGGER.info("RecipeFlow: No animated textures in atlas, item is not animated");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(0);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            LOGGER.info("RecipeFlow: Found {} animated textures in atlas", animatedTextures.size());

            // Find the tickAndUpload method on the animated texture tickers
            Method tickAndUploadMethod = null;
            if (!animatedTextures.isEmpty()) {
                Object firstTicker = animatedTextures.get(0);
                String[] possibleMethodNames = {"tickAndUpload", "m_245385_"};

                for (String methodName : possibleMethodNames) {
                    try {
                        tickAndUploadMethod = firstTicker.getClass().getDeclaredMethod(methodName);
                        tickAndUploadMethod.setAccessible(true);
                        break;
                    } catch (NoSuchMethodException ignored) {}
                }

                if (tickAndUploadMethod == null) {
                    LOGGER.warn("RecipeFlow: Could not find tickAndUpload method on animated texture ticker");
                    return renderAnimationSequenceFullBlock(stack);
                }
            }

            // Calculate how many game ticks needed to advance one animation frame
            // frameTimeMs is in milliseconds, 1 game tick = 50ms
            // So if frameTimeMs = 100 (2 ticks per frame), we need to tick 2 times to advance one visual frame
            int ticksPerAnimationFrame = Math.max(1, frameTimeMs / 50);

            LOGGER.info("RecipeFlow: Ticking {} times per animation frame (frameTimeMs={})",
                    ticksPerAnimationFrame, frameTimeMs);

            // Bind the atlas texture before ticking
            RenderSystem.bindTexture(blockAtlasTexture.getId());

            // Capture frames by ticking all animated textures
            for (int frame = 0; frame < frameCount; frame++) {
                // Tick all animated textures multiple times to advance them by one full animation frame
                // tickAndUpload() advances by 1 game tick, but animations may use frametime > 1
                for (int tick = 0; tick < ticksPerAnimationFrame; tick++) {
                    for (Object ticker : animatedTextures) {
                        try {
                            tickAndUploadMethod.invoke(ticker);
                        } catch (Exception e) {
                            // Some tickers might fail, continue with others
                        }
                    }
                }

                // Re-bind atlas after ticking (tick may change GL state)
                RenderSystem.bindTexture(blockAtlasTexture.getId());

                // Render the item - all animated textures are now at the same tick
                BufferedImage renderedFrame = renderItem(stack);
                sequenceFrames.add(renderedFrame);
                frameDurations.add(frameTimeMs);

                LOGGER.debug("RecipeFlow: Captured universal frame {} for {} (after {} ticks)",
                        frame, stack.getItem(), ticksPerAnimationFrame);
            }

            LOGGER.info("RecipeFlow: Completed universal animation capture for {} with {} frames",
                    stack.getItem(), sequenceFrames.size());

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Error in universal animation rendering for {}: {}",
                    stack.getItem(), e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }

            // Fall back to single frame
            if (sequenceFrames.isEmpty()) {
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(0);
            }
        }

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Universal animation rendering with auto-detected frame count.
     * Tries multiple approaches in order of preference:
     * 1. GTCEu overlay sprite ticking (renders 3D block with animated textures)
     * 2. Global texture atlas tick method
     *
     * Note: Texture-based extraction (flat 2D compositing) was attempted but doesn't
     * produce correct results for 3D blocks - it composites flat textures instead of
     * rendering the actual 3D model. The sprite-ticking approach properly renders
     * the 3D block while advancing the animation frames.
     *
     * @param stack The item stack to render
     * @return AnimationSequence with rendered frames
     */
    public AnimationSequence renderAnimationSequenceUniversal(ItemStack stack) {
        // Try to detect frame count from known animated sprites
        int frameCount = getFrameCount(stack);
        List<TextureAtlasSprite> gtSprites = null;

        // If we couldn't detect frames from model sprites, check GTCEu overlays
        if (isGTCEuLoaded()) {
            gtSprites = GTCEuIconHelper.getOverlaySprites(stack);
            for (TextureAtlasSprite sprite : gtSprites) {
                int spriteFrames = (int) sprite.contents().getUniqueFrames().count();
                if (spriteFrames > frameCount) {
                    frameCount = spriteFrames;
                }
            }
        }

        // Default frame time: 100ms (2 ticks) is common for many animations
        int frameTimeMs = getFrameTimeMs(stack);
        if (frameTimeMs <= 0) {
            frameTimeMs = 100; // Default 100ms per frame
        }

        if (frameCount <= 1) {
            // Not animated, return single frame
            List<BufferedImage> frames = new ArrayList<>();
            frames.add(renderItem(stack));
            List<Integer> durations = new ArrayList<>();
            durations.add(0);
            return new AnimationSequence(frames, durations);
        }

        // If we have GTCEu sprites, use the targeted direct frame upload method
        if (gtSprites != null && !gtSprites.isEmpty()) {
            LOGGER.info("RecipeFlow: Using targeted GTCEu sprite rendering with {} sprites, {} frames",
                    gtSprites.size(), frameCount);
            return renderAnimationSequenceWithSprites(stack, gtSprites, frameCount, frameTimeMs);
        }

        // Fall back to global tick method for non-GTCEu items
        return renderAnimationSequenceUniversal(stack, frameCount, frameTimeMs);
    }

    /**
     * Render animation sequence by finding and ticking the specific SpriteTicker for each sprite.
     *
     * The TextureAtlas maintains a list of Ticker objects (one per animated sprite).
     * Each Ticker holds a reference to its SpriteContents. We can match our sprites
     * to their tickers and call tickAndUpload only on those specific tickers.
     *
     * @param stack The item stack to render
     * @param sprites The animated sprites to find tickers for
     * @param frameCount Number of frames to capture
     * @param frameTimeMs Time per frame in milliseconds
     * @return AnimationSequence with rendered frames
     */
    private AnimationSequence renderAnimationSequenceWithSprites(ItemStack stack,
            List<TextureAtlasSprite> sprites, int frameCount, int frameTimeMs) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        LOGGER.info("RecipeFlow: Rendering animation by ticking specific sprite tickers for {} sprites, {} frames ({}ms per frame)",
                sprites.size(), frameCount, frameTimeMs);

        try {
            // Get the texture atlas
            var textureManager = minecraft.getTextureManager();
            var blockAtlasTexture = textureManager.getTexture(InventoryMenu.BLOCK_ATLAS);

            if (blockAtlasTexture == null) {
                LOGGER.warn("RecipeFlow: Could not get block atlas texture");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(0);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            // Find the animatedTextures field (list of Ticker objects)
            Field animatedTexturesField = null;
            String[] possibleFieldNames = {"animatedTextures", "f_118262_"};

            for (String fieldName : possibleFieldNames) {
                try {
                    animatedTexturesField = blockAtlasTexture.getClass().getDeclaredField(fieldName);
                    animatedTexturesField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            if (animatedTexturesField == null) {
                LOGGER.warn("RecipeFlow: Could not find animatedTextures field");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(frameTimeMs);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            @SuppressWarnings("unchecked")
            List<Object> allTickers = (List<Object>) animatedTexturesField.get(blockAtlasTexture);

            if (allTickers == null || allTickers.isEmpty()) {
                LOGGER.warn("RecipeFlow: No animated texture tickers in atlas");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(0);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            LOGGER.info("RecipeFlow: Atlas has {} total animated texture tickers", allTickers.size());

            // Find the tickers that correspond to our sprites
            // Each Ticker has a reference to its SpriteContents
            List<Object> matchingTickers = new ArrayList<>();
            Set<String> targetSpriteNames = new HashSet<>();
            for (TextureAtlasSprite sprite : sprites) {
                String spriteName = sprite.contents().name().toString();
                targetSpriteNames.add(spriteName);
                LOGGER.info("RecipeFlow: Looking for ticker for sprite: {}", spriteName);
            }

            // Get the tickAndUpload method (will be set per-class as needed)
            Method tickAndUploadMethod = null;

            // Cache for field lookups per ticker class (different mods have different ticker classes)
            Map<Class<?>, Field> spriteFieldCache = new HashMap<>();

            // Track stats for debugging
            Set<String> loggedTickerClasses = new HashSet<>();
            int tickersWithContents = 0;
            int tickersWithNames = 0;

            for (Object ticker : allTickers) {
                Class<?> tickerClass = ticker.getClass();

                // Log each unique ticker class once
                if (!loggedTickerClasses.contains(tickerClass.getName())) {
                    LOGGER.info("RecipeFlow: Ticker class: {}", tickerClass.getName());
                    LOGGER.info("RecipeFlow: Ticker fields: {}", java.util.Arrays.toString(tickerClass.getDeclaredFields()));
                    loggedTickerClasses.add(tickerClass.getName());
                }

                // Find the TextureAtlasSprite field for THIS ticker's class (cached per class)
                Field contentsField = spriteFieldCache.get(tickerClass);
                if (contentsField == null && !spriteFieldCache.containsKey(tickerClass)) {
                    // Use ObfuscationHelper constants for the ticker wrapper sprite field
                    contentsField = ObfuscationHelper.findField(tickerClass,
                            ObfuscationHelper.TICKER_WRAPPER_SPRITE);

                    if (contentsField != null) {
                        // Verify it's actually a TextureAtlasSprite
                        try {
                            Object testValue = contentsField.get(ticker);
                            if (testValue instanceof TextureAtlasSprite) {
                                LOGGER.info("RecipeFlow: Found TextureAtlasSprite field '{}' for class {}",
                                        contentsField.getName(), tickerClass.getSimpleName());
                            } else {
                                contentsField = null; // Reset if wrong type
                            }
                        } catch (Exception e) {
                            contentsField = null;
                        }
                    }

                    // If not found, search all fields for TextureAtlasSprite type
                    if (contentsField == null) {
                        for (Field field : tickerClass.getDeclaredFields()) {
                            try {
                                field.setAccessible(true);
                                Object value = field.get(ticker);
                                if (value instanceof TextureAtlasSprite) {
                                    contentsField = field;
                                    LOGGER.info("RecipeFlow: Found TextureAtlasSprite field by type '{}' for class {}",
                                            field.getName(), tickerClass.getSimpleName());
                                    break;
                                }
                            } catch (Exception e) {
                                // Skip inaccessible fields
                            }
                        }
                    }

                    // Cache the result (even if null, to avoid re-searching)
                    spriteFieldCache.put(tickerClass, contentsField);
                }

                // Find tickAndUpload method if not yet found (try on this class)
                if (tickAndUploadMethod == null) {
                    // Use ObfuscationHelper for tickAndUpload method - try without params first
                    tickAndUploadMethod = ObfuscationHelper.findMethod(tickerClass,
                            ObfuscationHelper.TICKER_TICK_AND_UPLOAD);

                    if (tickAndUploadMethod != null) {
                        LOGGER.info("RecipeFlow: Found tickAndUpload method: {}", tickAndUploadMethod.getName());
                    } else {
                        // Try with int parameters (x, y coordinates)
                        tickAndUploadMethod = ObfuscationHelper.findMethod(tickerClass,
                                new Class<?>[] { int.class, int.class },
                                ObfuscationHelper.TICKER_TICK_AND_UPLOAD);

                        if (tickAndUploadMethod != null) {
                            LOGGER.info("RecipeFlow: Found tickAndUpload method with params: {}", tickAndUploadMethod.getName());
                        }
                    }
                }

                // Check if this ticker's sprite matches one we're looking for
                if (contentsField != null) {
                    try {
                        Object spriteObj = contentsField.get(ticker);
                        if (spriteObj instanceof TextureAtlasSprite sprite) {
                            tickersWithContents++;
                            // Get the name from TextureAtlasSprite.contents().name()
                            String spriteName = sprite.contents().name().toString();
                            tickersWithNames++;

                            if (targetSpriteNames.contains(spriteName)) {
                                matchingTickers.add(ticker);
                                LOGGER.info("RecipeFlow: Found matching ticker for sprite: {}", spriteName);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("RecipeFlow: Error accessing sprite from ticker {}: {}",
                                tickerClass.getSimpleName(), e.getMessage());
                    }
                }
            }

            LOGGER.info("RecipeFlow: Ticker analysis: {} total, {} with contents, {} with names",
                    allTickers.size(), tickersWithContents, tickersWithNames);

            if (matchingTickers.isEmpty()) {
                LOGGER.warn("RecipeFlow: Could not find tickers for any of the target sprites: {}", targetSpriteNames);
                // Fall back to ticking ALL tickers (may cause OpenGL errors but might work)
                matchingTickers.addAll(allTickers);
                LOGGER.info("RecipeFlow: Falling back to ticking all {} tickers", matchingTickers.size());
            } else {
                LOGGER.info("RecipeFlow: Found {} matching tickers for our sprites", matchingTickers.size());
            }

            if (tickAndUploadMethod == null) {
                LOGGER.warn("RecipeFlow: Could not find tickAndUpload method");
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(frameTimeMs);
                return new AnimationSequence(sequenceFrames, frameDurations);
            }

            // Calculate ticks per animation frame
            int ticksPerFrame = Math.max(1, frameTimeMs / 50);
            LOGGER.info("RecipeFlow: Will tick {} times per animation frame", ticksPerFrame);

            // Try to get the inner SpriteTicker from the wrapper for more control
            // The wrapper (TextureAtlasSprite$1) has val$spriteticker which is the actual ticker
            Field innerTickerField = ObfuscationHelper.findField(matchingTickers.get(0).getClass(),
                    ObfuscationHelper.TICKER_WRAPPER_INNER_TICKER);

            // Also get the sprite reference to get atlas coordinates
            Field wrapperSpriteField = spriteFieldCache.get(matchingTickers.get(0).getClass());

            // Try to get the AnimatedTexture from the inner ticker to directly upload frames
            // The AnimatedTexture has uploadFrame(int x, int y, int frameIndex) which is what we need
            Object animatedTexture = null;
            Method animatedTextureUploadFrame = null;
            Field animationInfoField = null;

            if (innerTickerField != null && wrapperSpriteField != null) {
                try {
                    Object innerTicker = innerTickerField.get(matchingTickers.get(0));
                    Object spriteObj = wrapperSpriteField.get(matchingTickers.get(0));

                    if (innerTicker != null && spriteObj instanceof TextureAtlasSprite) {
                        LOGGER.info("RecipeFlow: Found inner SpriteTicker: {}", innerTicker.getClass().getName());

                        // Log all fields on the Ticker for debugging
                        LOGGER.info("RecipeFlow: Inner Ticker fields: {}",
                                java.util.Arrays.toString(innerTicker.getClass().getDeclaredFields()));

                        // The AnimatedTexture IS on the Ticker as f_243921_ (SpriteContents$AnimatedTexture)
                        // Get the animatedTexture field from the inner Ticker
                        animationInfoField = ObfuscationHelper.findField(innerTicker.getClass(),
                                ObfuscationHelper.TICKER_ANIMATION_INFO);

                        if (animationInfoField != null) {
                            animatedTexture = animationInfoField.get(innerTicker);
                            if (animatedTexture != null) {
                                LOGGER.info("RecipeFlow: Found AnimatedTexture: {}", animatedTexture.getClass().getName());

                                // Log all methods on AnimatedTexture
                                LOGGER.info("RecipeFlow: AnimatedTexture methods: {}",
                                        java.util.Arrays.toString(animatedTexture.getClass().getDeclaredMethods()));

                                // Find uploadFrame(int x, int y, int frameIndex) on AnimatedTexture
                                // Use ObfuscationHelper which wraps Forge's ObfuscationReflectionHelper
                                animatedTextureUploadFrame = ObfuscationHelper.findMethod(
                                        animatedTexture.getClass(),
                                        new Class<?>[] { int.class, int.class, int.class },
                                        ObfuscationHelper.ANIMATED_TEXTURE_UPLOAD_FRAME);

                                if (animatedTextureUploadFrame != null) {
                                    LOGGER.info("RecipeFlow: Found AnimatedTexture.uploadFrame method: {}", animatedTextureUploadFrame.getName());
                                } else {
                                    LOGGER.warn("RecipeFlow: Could not find uploadFrame on AnimatedTexture. Available methods: {}",
                                            java.util.Arrays.toString(animatedTexture.getClass().getDeclaredMethods()));
                                }
                            } else {
                                LOGGER.warn("RecipeFlow: animatedTexture field is null on Ticker");
                            }
                        } else {
                            LOGGER.warn("RecipeFlow: Could not find animatedTexture field on Ticker");
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow: Could not access AnimatedTexture: {}", e.getMessage());
                }
            }

            // Log what we have before the frame loop
            LOGGER.info("RecipeFlow: Pre-loop state - innerTickerField: {}, animatedTexture: {}, uploadMethod: {}, wrapperSpriteField: {}",
                    innerTickerField != null, animatedTexture != null, animatedTextureUploadFrame != null, wrapperSpriteField != null);

            // Capture frames by directly uploading specific frame indices
            for (int frame = 0; frame < frameCount; frame++) {
                // Bind the atlas texture
                RenderSystem.bindTexture(blockAtlasTexture.getId());

                // Upload the specific frame for each matching ticker
                boolean uploadedSuccessfully = false;
                for (Object ticker : matchingTickers) {
                    try {
                        if (animatedTextureUploadFrame != null && wrapperSpriteField != null && innerTickerField != null && animationInfoField != null) {
                            // Get the sprite and inner ticker for this specific wrapper
                            Object spriteObj = wrapperSpriteField.get(ticker);
                            Object innerTicker = innerTickerField.get(ticker);

                            if (spriteObj instanceof TextureAtlasSprite sprite && innerTicker != null) {
                                // Get AnimatedTexture from the inner Ticker (f_243921_)
                                Object tickerAnimatedTexture = animationInfoField.get(innerTicker);

                                if (frame == 0) {
                                    LOGGER.info("RecipeFlow: tickerAnimatedTexture: {}, sprite: {}",
                                            tickerAnimatedTexture != null ? tickerAnimatedTexture.getClass().getSimpleName() : "null",
                                            sprite.contents().name());
                                }

                                if (tickerAnimatedTexture != null) {
                                    // Directly upload the specific frame using AnimatedTexture.uploadFrame(x, y, frameIndex)
                                    animatedTextureUploadFrame.invoke(tickerAnimatedTexture, sprite.getX(), sprite.getY(), frame);
                                    uploadedSuccessfully = true;
                                    LOGGER.info("RecipeFlow: Uploaded frame {} at ({}, {}) for {}",
                                            frame, sprite.getX(), sprite.getY(), sprite.contents().name());
                                    continue;  // Skip fallback for this ticker
                                } else {
                                    if (frame == 0) {
                                        LOGGER.warn("RecipeFlow: AnimatedTexture is null for sprite {}", sprite.contents().name());
                                    }
                                }
                            } else {
                                if (frame == 0) {
                                    LOGGER.warn("RecipeFlow: spriteObj or innerTicker issue: sprite={}, innerTicker={}",
                                            spriteObj != null ? spriteObj.getClass().getName() : "null",
                                            innerTicker != null ? innerTicker.getClass().getName() : "null");
                                }
                            }
                        }
                        // Fallback to wrapper method (tick multiple times) - only if direct upload didn't work
                        if (frame == 0) {
                            LOGGER.info("RecipeFlow: Using fallback wrapper method");
                        }
                        for (int tick = 0; tick < ticksPerFrame; tick++) {
                            tickAndUploadMethod.invoke(ticker);
                        }
                    } catch (Exception e) {
                        if (frame == 0) {
                            // Get the real cause if it's an InvocationTargetException
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            LOGGER.warn("RecipeFlow: Frame set/upload failed: {} - {}",
                                    cause.getClass().getSimpleName(), cause.getMessage());
                            LOGGER.warn("RecipeFlow: Stack trace:", cause);
                        }
                    }
                }

                if (frame == 0) {
                    LOGGER.info("RecipeFlow: Frame {} upload status: direct={}", frame, uploadedSuccessfully);
                }

                // Ensure GPU has the updated texture before rendering
                if (uploadedSuccessfully) {
                    // Use glFinish to block until texture upload is complete
                    GL11.glFinish();
                }

                // Re-bind after uploading and log texture ID for debugging
                int atlasId = blockAtlasTexture.getId();
                RenderSystem.bindTexture(atlasId);
                if (frame == 0) {
                    LOGGER.info("RecipeFlow: Atlas texture ID: {}", atlasId);
                }

                // Render the item
                BufferedImage renderedFrame = renderItem(stack);
                sequenceFrames.add(renderedFrame);
                frameDurations.add(frameTimeMs);

                LOGGER.info("RecipeFlow: Captured frame {} for {} tickers", frame, matchingTickers.size());
            }

            LOGGER.info("RecipeFlow: Completed ticker-based animation capture with {} frames", sequenceFrames.size());

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Error in ticker-based animation rendering: {}", e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
            if (sequenceFrames.isEmpty()) {
                sequenceFrames.add(renderItem(stack));
                frameDurations.add(frameTimeMs);
            }
        }

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Helper class to hold animation info for a sprite.
     */
    private static class SpriteAnimationInfo {
        final Object animatedTexture;
        final Method uploadFrameMethod;
        final int atlasX;
        final int atlasY;
        final int frameCount;

        SpriteAnimationInfo(Object animatedTexture, Method uploadFrameMethod,
                           int atlasX, int atlasY, int frameCount) {
            this.animatedTexture = animatedTexture;
            this.uploadFrameMethod = uploadFrameMethod;
            this.atlasX = atlasX;
            this.atlasY = atlasY;
            this.frameCount = frameCount;
        }
    }

    /**
     * Prepare animation info for a sprite (find the animatedTexture and uploadFrame method).
     */
    private SpriteAnimationInfo prepareSpritAnimationInfo(TextureAtlasSprite sprite) {
        try {
            var contents = sprite.contents();

            // Find the animatedTexture field
            Object animatedTexture = null;
            String[] possibleFieldNames = {"animatedTexture", "f_244575_"};

            for (String fieldName : possibleFieldNames) {
                try {
                    Field field = contents.getClass().getDeclaredField(fieldName);
                    field.setAccessible(true);
                    animatedTexture = field.get(contents);
                    if (animatedTexture != null) {
                        break;
                    }
                } catch (NoSuchFieldException ignored) {}
            }

            if (animatedTexture == null) {
                LOGGER.debug("RecipeFlow: Could not find animatedTexture for sprite {}", sprite.contents().name());
                return null;
            }

            // Find the uploadFrame method
            Method uploadFrameMethod = null;
            String[] possibleMethodNames = {"uploadFrame", "m_245074_"};

            for (String methodName : possibleMethodNames) {
                try {
                    uploadFrameMethod = animatedTexture.getClass().getDeclaredMethod(methodName, int.class, int.class, int.class);
                    uploadFrameMethod.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }

            if (uploadFrameMethod == null) {
                LOGGER.debug("RecipeFlow: Could not find uploadFrame method for sprite {}", sprite.contents().name());
                return null;
            }

            int frameCount = (int) sprite.contents().getUniqueFrames().count();

            return new SpriteAnimationInfo(
                    animatedTexture,
                    uploadFrameMethod,
                    sprite.getX(),
                    sprite.getY(),
                    frameCount
            );

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Error preparing animation info for sprite {}: {}",
                    sprite.contents().name(), e.getMessage());
            return null;
        }
    }

    /**
     * Extract individual frames from an animated sprite.
     * Reads directly from the sprite's NativeImage data.
     */
    private List<BufferedImage> extractSpriteFrames(TextureAtlasSprite sprite, int frameCount) {
        List<BufferedImage> frames = new ArrayList<>();

        try {
            // Get sprite dimensions (single frame size)
            int spriteWidth = sprite.contents().width();
            int spriteHeight = sprite.contents().height();

            // Get the original image which contains all frames vertically stacked
            var contents = sprite.contents();
            var originalImage = contents.getOriginalImage();

            if (originalImage == null) {
                LOGGER.warn("RecipeFlow: Sprite original image is null");
                return frames;
            }

            // Original image height should be spriteHeight * frameCount for vertical strip
            int imageHeight = originalImage.getHeight();
            int imageWidth = originalImage.getWidth();

            LOGGER.debug("RecipeFlow: Sprite size: {}x{}, Image size: {}x{}, Frames: {}",
                    spriteWidth, spriteHeight, imageWidth, imageHeight, frameCount);

            // Extract each frame from the vertical strip
            for (int frame = 0; frame < frameCount; frame++) {
                BufferedImage frameImage = new BufferedImage(spriteWidth, spriteHeight, BufferedImage.TYPE_INT_ARGB);

                int frameYOffset = frame * spriteHeight;

                // Check bounds
                if (frameYOffset + spriteHeight > imageHeight) {
                    LOGGER.warn("RecipeFlow: Frame {} exceeds image bounds", frame);
                    break;
                }

                // Copy pixels from NativeImage to BufferedImage
                for (int y = 0; y < spriteHeight; y++) {
                    for (int x = 0; x < spriteWidth; x++) {
                        // NativeImage stores ABGR, BufferedImage needs ARGB
                        int abgr = originalImage.getPixelRGBA(x, frameYOffset + y);

                        // Convert ABGR to ARGB
                        int a = (abgr >> 24) & 0xFF;
                        int b = (abgr >> 16) & 0xFF;
                        int g = (abgr >> 8) & 0xFF;
                        int r = abgr & 0xFF;
                        int argb = (a << 24) | (r << 16) | (g << 8) | b;

                        frameImage.setRGB(x, y, argb);
                    }
                }

                // Scale up to icon size (sprites are usually 16x16, we want 128x128)
                int targetSize = ICON_SIZE_DEFAULT;
                BufferedImage scaledFrame = scaleImage(frameImage, targetSize, targetSize);
                frames.add(scaledFrame);
            }

        } catch (Exception e) {
            LOGGER.error("RecipeFlow: Error extracting sprite frames: {}", e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }
        }

        return frames;
    }

    /**
     * Scale an image using nearest neighbor for crisp pixel art.
     */
    private BufferedImage scaleImage(BufferedImage source, int targetWidth, int targetHeight) {
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);

        double scaleX = (double) source.getWidth() / targetWidth;
        double scaleY = (double) source.getHeight() / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int srcX = (int) (x * scaleX);
                int srcY = (int) (y * scaleY);
                srcX = Math.min(srcX, source.getWidth() - 1);
                srcY = Math.min(srcY, source.getHeight() - 1);
                scaled.setRGB(x, y, source.getRGB(srcX, srcY));
            }
        }

        return scaled;
    }

    /**
     * Render a batch of items efficiently using texture atlas batching.
     * Items are grouped by icon size, rendered to atlases, and extracted.
     * This significantly reduces GPU→CPU transfer overhead.
     *
     * @param stacks List of item stacks to render
     * @return Map of ItemStack index to rendered BufferedImage
     */
    public Map<Integer, BufferedImage> renderItemBatch(List<ItemStack> stacks) {
        Map<Integer, BufferedImage> results = new HashMap<>();

        if (stacks.isEmpty()) {
            return results;
        }

        // Group items by their target icon size
        Map<Integer, List<Integer>> sizeGroups = new HashMap<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                int iconSize = getIconSizeForItem(stack);
                sizeGroups.computeIfAbsent(iconSize, k -> new ArrayList<>()).add(i);
            }
        }

        // Log size group info for debugging
        for (Map.Entry<Integer, List<Integer>> entry : sizeGroups.entrySet()) {
            LOGGER.info("RecipeFlow: Size group {}px has {} items", entry.getKey(), entry.getValue().size());
        }

        // Process each size group
        for (Map.Entry<Integer, List<Integer>> entry : sizeGroups.entrySet()) {
            int iconSize = entry.getKey();
            List<Integer> indices = entry.getValue();

            // Calculate atlas grid dimensions
            // Limit atlas size to avoid GPU memory issues
            int maxItemsPerRow = MAX_ATLAS_SIZE / iconSize;
            int itemsPerAtlas = maxItemsPerRow * maxItemsPerRow;

            // Process in atlas-sized batches
            for (int batchStart = 0; batchStart < indices.size(); batchStart += itemsPerAtlas) {
                int batchEnd = Math.min(batchStart + itemsPerAtlas, indices.size());
                int batchSize = batchEnd - batchStart;

                // Calculate grid size for this batch
                int gridSize = (int) Math.ceil(Math.sqrt(batchSize));
                int atlasSize = gridSize * iconSize;

                // Render batch to atlas
                Map<Integer, BufferedImage> batchResults = renderAtlasBatch(
                    stacks, indices, batchStart, batchEnd, gridSize, iconSize, atlasSize
                );

                results.putAll(batchResults);
            }
        }

        return results;
    }

    /**
     * Render a batch of items to a texture atlas and extract individual icons.
     */
    private Map<Integer, BufferedImage> renderAtlasBatch(
            List<ItemStack> stacks,
            List<Integer> indices,
            int batchStart,
            int batchEnd,
            int gridSize,
            int iconSize,
            int atlasSize
    ) {
        Map<Integer, BufferedImage> results = new HashMap<>();

        ensureRenderTarget(atlasSize);

        try {
            // Bind render target
            renderTarget.bindWrite(true);

            // Clear the buffer
            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            // Enable depth testing
            RenderSystem.enableDepthTest();

            // Set up orthographic projection for the full atlas
            // Use large depth range to avoid clipping rotated 3D models
            Matrix4f projectionMatrix = new Matrix4f().ortho(
                    0.0f, atlasSize, 0.0f, atlasSize, -1000.0f, 1000.0f
            );
            RenderSystem.setProjectionMatrix(projectionMatrix, com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);

            // Build a local list of items for this batch to ensure correct ordering
            List<Integer> batchIndices = new ArrayList<>();
            for (int i = batchStart; i < batchEnd; i++) {
                batchIndices.add(indices.get(i));
            }

            // Render each item to its grid position
            for (int localIndex = 0; localIndex < batchIndices.size(); localIndex++) {
                int gridX = localIndex % gridSize;
                int gridY = localIndex / gridSize;

                int offsetX = gridX * iconSize;
                int offsetY = gridY * iconSize;

                int stackIndex = batchIndices.get(localIndex);
                ItemStack stack = stacks.get(stackIndex);

                renderItemAtPosition(stack, iconSize, offsetX, offsetY);
            }

            // Single pixel readback for entire atlas
            BufferedImage atlas = readPixels(atlasSize);

            // Unbind render target
            renderTarget.unbindWrite();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

            // Extract individual icons from atlas (no downscaling needed)
            // readPixels flips Y: OpenGL coords (Y=0 at bottom) -> BufferedImage coords (Y=0 at top)
            // Items at gridY=0 are rendered at OpenGL y=0 (bottom), which becomes BufferedImage BOTTOM after flip
            for (int localIndex = 0; localIndex < batchIndices.size(); localIndex++) {
                int gridX = localIndex % gridSize;
                int gridY = localIndex / gridSize;

                int srcX = gridX * iconSize;
                // After Y-flip in readPixels: gridY=0 (rendered at OpenGL bottom) is now at BufferedImage bottom
                // So we need to invert: srcY = (gridSize - 1 - gridY) * iconSize
                int srcY = (gridSize - 1 - gridY) * iconSize;

                // Extract tile from atlas - create a copy to avoid subimage memory issues
                BufferedImage tile = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = tile.createGraphics();
                g.drawImage(atlas.getSubimage(srcX, srcY, iconSize, iconSize), 0, 0, null);
                g.dispose();

                int stackIndex = batchIndices.get(localIndex);
                results.put(stackIndex, tile);
            }

        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to render atlas batch: {}", e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow: Stack trace:", e);
            }

            // Return empty images for failed items
            for (int i = batchStart; i < batchEnd; i++) {
                int stackIndex = indices.get(i);
                if (!results.containsKey(stackIndex)) {
                    results.put(stackIndex, createEmptyImage(iconSize));
                }
            }

            // Try to restore render target state
            try {
                if (renderTarget != null) {
                    renderTarget.unbindWrite();
                }
                Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            } catch (Exception ignored) {}
        }

        return results;
    }

    /**
     * Render an item at a specific position in the atlas.
     * Mimics Minecraft's ItemRenderer.renderGuiItem() approach.
     */
    private void renderItemAtPosition(ItemStack stack, int iconSize, int offsetX, int offsetY) {
        PoseStack poseStack = new PoseStack();

        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, null, null, 0);

        // Set up lighting based on model type
        // usesBlockLight() determines if item uses "side" lighting (3D blocks) or "front" lighting (flat items)
        if (model.usesBlockLight()) {
            Lighting.setupFor3DItems();
        } else {
            Lighting.setupForFlatItems();
        }

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Position at tile location, then set up like Minecraft's renderGuiItem
        // Minecraft renders at (x+8, y+8) with scale 16, then flips Y
        poseStack.translate(offsetX + iconSize / 2.0f, offsetY + iconSize / 2.0f, 100.0f);

        // Scale to fill the icon size (base item is 16x16)
        float scale = iconSize / 16.0f;
        poseStack.scale(scale, scale, scale);

        // Apply the standard Minecraft GUI item transform:
        // Scale by 16 and flip Y (this is what renderGuiItem does)
        if (model.isGui3d()) {
            // For 3D models, apply rotation to show top-front-right view like vanilla
            // Rotate 180 degrees around X to flip upside down
            poseStack.mulPose(new org.joml.Quaternionf().rotationX((float) Math.toRadians(180)));
            poseStack.scale(16.0f, -16.0f, -16.0f);
        } else {
            poseStack.scale(16.0f, 16.0f, 16.0f);  // Standard transform for 2D items
        }

        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        itemRenderer.render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model
        );

        bufferSource.endBatch();
    }

    /**
     * Get ALL texture sprites used by an item's model.
     * This includes sprites from all quads (all faces and overlays).
     * Important for detecting animations on modded blocks that use overlay textures.
     *
     * @param stack The item stack
     * @return List of all unique sprites used by the model
     */
    private List<TextureAtlasSprite> getAllModelSprites(ItemStack stack) {
        Set<TextureAtlasSprite> sprites = new HashSet<>();

        try {
            ItemRenderer itemRenderer = minecraft.getItemRenderer();
            BakedModel model = itemRenderer.getModel(stack, null, null, 0);

            // Add particle icon first (fallback)
            // Use Forge's extended getParticleIcon with ModelData for mod compatibility
            TextureAtlasSprite particleIcon = model.getParticleIcon(ModelData.EMPTY);
            if (particleIcon != null) {
                sprites.add(particleIcon);
            }

            // Get sprites from all quads (null direction = non-culled faces)
            // Use Forge's extended getQuads with ModelData for mod compatibility (e.g., GTCEu)
            var random = net.minecraft.util.RandomSource.create();
            var nullQuads = model.getQuads(null, null, random, ModelData.EMPTY, null);
            for (var quad : nullQuads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite != null) {
                    sprites.add(sprite);
                }
            }

            // Get sprites from directional quads (all 6 faces)
            for (Direction direction : Direction.values()) {
                var dirQuads = model.getQuads(null, direction, random, ModelData.EMPTY, null);
                for (var quad : dirQuads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite != null) {
                        sprites.add(sprite);
                    }
                }
            }

            // For BlockItems, also check the block's default state model
            // Some mods like GTCEu use block models that have different quads than the item model
            if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
                try {
                    var block = blockItem.getBlock();
                    var defaultState = block.defaultBlockState();
                    var blockRenderer = minecraft.getBlockRenderer();
                    var blockModel = blockRenderer.getBlockModel(defaultState);

                    if (blockModel != null && blockModel != model) {
                        LOGGER.debug("RecipeFlow: Block model differs from item model for {}", stack.getItem());

                        // Get particle from block model
                        // Use Forge's extended getParticleIcon with ModelData for mod compatibility
                        TextureAtlasSprite blockParticle = blockModel.getParticleIcon(ModelData.EMPTY);
                        if (blockParticle != null) {
                            sprites.add(blockParticle);
                        }

                        // Get quads from block model for all faces
                        // Use Forge's extended getQuads with ModelData for mod compatibility
                        var blockRandom = net.minecraft.util.RandomSource.create();
                        var blockNullQuads = blockModel.getQuads(defaultState, null, blockRandom, ModelData.EMPTY, null);
                        for (var quad : blockNullQuads) {
                            TextureAtlasSprite sprite = quad.getSprite();
                            if (sprite != null) {
                                sprites.add(sprite);
                            }
                        }

                        for (Direction direction : Direction.values()) {
                            var dirQuads = blockModel.getQuads(defaultState, direction, blockRandom, ModelData.EMPTY, null);
                            for (var quad : dirQuads) {
                                TextureAtlasSprite sprite = quad.getSprite();
                                if (sprite != null) {
                                    sprites.add(sprite);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("RecipeFlow: Failed to get block model sprites: {}", e.getMessage());
                }
            }

            // Try to find overlay sprites for modded machines (e.g., GTCEu)
            // These mods often apply overlays at render time, not through the baked model
            List<TextureAtlasSprite> overlaySprites = findOverlaySprites(stack);
            sprites.addAll(overlaySprites);

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Failed to get model sprites for {}: {}", stack.getItem(), e.getMessage());
        }

        return new ArrayList<>(sprites);
    }

    /**
     * Try to find overlay sprites that modded items might use.
     * Some mods like GTCEu apply animated overlays at render time that aren't
     * included in the standard BakedModel quads.
     *
     * For GTCEu machines, uses the MachineDefinition API to get correctly-colored
     * overlays (green for input, red for output) rather than generic patterns.
     *
     * @param stack The item stack
     * @return List of overlay sprites found
     */
    private List<TextureAtlasSprite> findOverlaySprites(ItemStack stack) {
        List<TextureAtlasSprite> overlaySprites = new ArrayList<>();

        try {
            String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();

            // GTCEu machine detection - use dedicated helper for correct overlay colors
            if (itemId.startsWith("gtceu:") && isGTCEuLoaded()) {
                overlaySprites = GTCEuIconHelper.getOverlaySprites(stack);
                if (!overlaySprites.isEmpty()) {
                    LOGGER.debug("RecipeFlow: Found {} overlay sprites via GTCEuIconHelper for {}",
                            overlaySprites.size(), itemId);
                    return overlaySprites;
                }
                // Fall through to generic detection if API call returns empty
                LOGGER.debug("RecipeFlow: GTCEuIconHelper returned no overlays for {}, trying fallback", itemId);
            }

            // Fallback: Generic texture atlas scanning for non-GTCEu items
            // or when GTCEuIconHelper doesn't find overlays
            var textureAtlas = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

            if (itemId.startsWith("gtceu:")) {
                String itemName = itemId.substring(6); // Remove "gtceu:" prefix

                // Fallback patterns for when GTCEu API isn't available
                List<String> relevantOverlays = new ArrayList<>();

                if (itemName.contains("input_bus") || itemName.contains("item_bus") ||
                    itemName.contains("input") || itemName.contains("import")) {
                    relevantOverlays.add("gtceu:block/overlay/machine/overlay_pipe_in_emissive");
                } else if (itemName.contains("output_bus") || itemName.contains("output") ||
                           itemName.contains("export")) {
                    relevantOverlays.add("gtceu:block/overlay/machine/overlay_pipe_out_emissive");
                } else if (itemName.contains("buffer") || itemName.contains("chest") || itemName.contains("quantum")) {
                    relevantOverlays.add("gtceu:block/overlay/machine/overlay_buffer_emissive");
                    relevantOverlays.add("gtceu:block/overlay/machine/overlay_qchest_emissive");
                } else {
                    // For other machines, check common overlays
                    relevantOverlays.add("gtceu:block/overlay/machine/overlay_screen_emissive");
                }

                // Try to get each overlay sprite from the texture atlas
                for (String overlayPath : relevantOverlays) {
                    try {
                        var location = ResourceLocation.tryParse(overlayPath);
                        if (location != null) {
                            TextureAtlasSprite sprite = textureAtlas.apply(location);
                            // Check if sprite is valid and not missing texture
                            if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                                long frameCount = sprite.contents().getUniqueFrames().count();
                                if (frameCount > 1) {
                                    LOGGER.debug("RecipeFlow: Found animated overlay sprite {} with {} frames for {} (fallback)",
                                            overlayPath, frameCount, itemId);
                                    overlaySprites.add(sprite);
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Sprite not found, continue
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Error finding overlay sprites: {}", e.getMessage());
        }

        return overlaySprites;
    }

    /**
     * Check if GTCEu mod is loaded (cached for performance).
     */
    private boolean isGTCEuLoaded() {
        if (gtceuLoaded == null) {
            gtceuLoaded = ModList.get().isLoaded("gtceu");
            LOGGER.debug("RecipeFlow: GTCEu mod loaded: {}", gtceuLoaded);
        }
        return gtceuLoaded;
    }

    /**
     * Get the first animated sprite from an item's model.
     * Returns the sprite with the most frames if multiple animated sprites exist.
     *
     * @param stack The item stack
     * @return The animated sprite, or null if none found
     */
    private TextureAtlasSprite getAnimatedSprite(ItemStack stack) {
        TextureAtlasSprite bestSprite = null;
        long maxFrames = 0;

        for (TextureAtlasSprite sprite : getAllModelSprites(stack)) {
            if (sprite != null) {
                long frameCount = sprite.contents().getUniqueFrames().count();
                if (frameCount > maxFrames) {
                    maxFrames = frameCount;
                    bestSprite = sprite;
                }
            }
        }

        return bestSprite;
    }

    /**
     * Get ALL animated sprites from an item's model.
     * Returns all sprites that have more than 1 frame.
     *
     * @param stack The item stack
     * @return List of animated sprites, may be empty
     */
    public List<TextureAtlasSprite> getAllAnimatedSprites(ItemStack stack) {
        List<TextureAtlasSprite> animatedSprites = new ArrayList<>();

        for (TextureAtlasSprite sprite : getAllModelSprites(stack)) {
            if (sprite != null) {
                long frameCount = sprite.contents().getUniqueFrames().count();
                if (frameCount > 1) {
                    animatedSprites.add(sprite);
                }
            }
        }

        return animatedSprites;
    }

    /**
     * Ensure the render target is initialized with the correct size.
     */
    private void ensureRenderTarget(int size) {
        if (renderTarget == null || currentRenderTargetSize != size) {
            if (renderTarget != null) {
                renderTarget.destroyBuffers();
            }
            renderTarget = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            currentRenderTargetSize = size;
        }
    }

    /**
     * Internal method to render an item.
     * Mimics Minecraft's ItemRenderer.renderGuiItem() behavior for accurate icon rendering.
     */
    private void renderItemInternal(ItemStack stack, int iconSize) {
        PoseStack poseStack = new PoseStack();

        // Get item renderer and model
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, null, null, 0);

        // Set up lighting based on model type
        // usesBlockLight() determines if item uses "side" lighting (3D blocks) or "front" lighting (flat items)
        if (model.usesBlockLight()) {
            Lighting.setupFor3DItems();
        } else {
            Lighting.setupForFlatItems();
        }

        // Set up render state
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Move to center of our render target
        poseStack.translate(iconSize / 2.0f, iconSize / 2.0f, 100.0f);

        // Scale to fill the icon size (base item is 16x16)
        float scale = iconSize / 16.0f;
        poseStack.scale(scale, scale, scale);

        // Apply the standard Minecraft GUI item transform:
        // Scale by 16 and flip Y (this is what renderGuiItem does)
        if (model.isGui3d()) {
            // For 3D models, apply rotation to show top-front-right view like vanilla
            // Rotate 180 degrees around X to flip upside down
            poseStack.mulPose(new org.joml.Quaternionf().rotationX((float) Math.toRadians(180)));
            poseStack.scale(16.0f, -16.0f, -16.0f);
        } else {
            poseStack.scale(16.0f, 16.0f, 16.0f);  // Standard transform for 2D items
        }

        // Create buffer source
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        // Render the item
        itemRenderer.render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880, // Full brightness
                OverlayTexture.NO_OVERLAY,
                model
        );

        // Flush the buffer
        bufferSource.endBatch();
    }

    /**
     * Read pixels from the current framebuffer.
     */
    private BufferedImage readPixels(int iconSize) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(iconSize * iconSize * 4);
        buffer.order(ByteOrder.nativeOrder());

        GL11.glReadPixels(0, 0, iconSize, iconSize, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        BufferedImage image = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);

        // Flip vertically - OpenGL reads from bottom-left, BufferedImage expects top-left
        for (int y = 0; y < iconSize; y++) {
            for (int x = 0; x < iconSize; x++) {
                int srcIndex = ((iconSize - 1 - y) * iconSize + x) * 4;
                int r = buffer.get(srcIndex) & 0xFF;
                int g = buffer.get(srcIndex + 1) & 0xFF;
                int b = buffer.get(srcIndex + 2) & 0xFF;
                int a = buffer.get(srcIndex + 3) & 0xFF;
                int argb = (a << 24) | (r << 16) | (g << 8) | b;
                image.setRGB(x, y, argb);
            }
        }

        return image;
    }

    /**
     * Create an empty transparent image.
     */
    private BufferedImage createEmptyImage(int iconSize) {
        return new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        if (renderTarget != null) {
            renderTarget.destroyBuffers();
            renderTarget = null;
        }
    }
}

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
     * Render animation using DIRECT FRAME EXTRACTION from sprite source images.
     * This bypasses the texture atlas animation system entirely by:
     * 1. Rendering the item once to get the base 3D render
     * 2. Extracting each animation frame directly from the sprite's source NativeImage
     * 3. Compositing the overlay frames onto the base render
     *
     * This is the most reliable method for mods like GTCEu that use custom rendering
     * pipelines where uploadFrame() doesn't produce visible results.
     *
     * @param stack The item stack to render
     * @return AnimationSequence with rendered frames
     */
    public AnimationSequence renderAnimationSequenceDirectExtraction(ItemStack stack) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty()) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Get the animated sprite (the one with most frames)
        TextureAtlasSprite animatedSprite = getAnimatedSprite(stack);
        if (animatedSprite == null) {
            LOGGER.info("RecipeFlow DirectExtraction: No animated sprite found for {}", stack.getItem());
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        String spriteName = animatedSprite.contents().name().toString();
        LOGGER.info("RecipeFlow DirectExtraction: Using sprite {} for {}", spriteName, stack.getItem());

        // Extract frames directly from the sprite's source image
        DirectFrameExtractor.ExtractionResult extraction = DirectFrameExtractor.extractFrames(animatedSprite);

        if (!extraction.success()) {
            LOGGER.warn("RecipeFlow DirectExtraction: Failed to extract frames for {}: {}",
                    stack.getItem(), extraction.errorMessage());
            // Fall back to single rendered frame
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        LOGGER.info("RecipeFlow DirectExtraction: Extracted {} frames ({}x{}) for {}",
                extraction.frameCount(), extraction.spriteWidth(), extraction.spriteHeight(), stack.getItem());

        // Render the base item (this will show frame 0 of the animation)
        BufferedImage baseRender = renderItem(stack);
        int iconSize = baseRender.getWidth();

        // For each extracted frame, we have two options:
        // Option A: Return the raw sprite frames (flat 2D textures)
        // Option B: Composite the frames onto the base render (preserves 3D appearance)
        //
        // For now, we'll use Option A with scaling since it's simpler and works
        // for most cases. Option B would require knowing exactly where the overlay
        // appears on the rendered item, which varies by item/model.

        for (int i = 0; i < extraction.frameCount(); i++) {
            BufferedImage frame = extraction.frames().get(i);
            int durationMs = extraction.frameDurationsMs().get(i);

            // Scale the extracted frame to match the icon size
            BufferedImage scaledFrame = DirectFrameExtractor.scale(frame, iconSize, iconSize);

            // For now, just use the base render for each frame since we can't
            // easily composite the overlay at the correct position.
            // The animation comes from the texture variation.
            //
            // TODO: Implement proper compositing by:
            // 1. Determining overlay position on the rendered item
            // 2. Masking the overlay region
            // 3. Compositing the new frame onto that region
            sequenceFrames.add(scaledFrame);
            frameDurations.add(durationMs);
        }

        // If we only got sprite frames (not full renders), log that
        if (!sequenceFrames.isEmpty()) {
            LOGGER.info("RecipeFlow DirectExtraction: Returning {} extracted frames for {} " +
                    "(sprite frames only, not full 3D renders)",
                    sequenceFrames.size(), stack.getItem());
        }

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Render animation by MODIFYING THE SOURCE NATIVEIMAGE before each render.
     *
     * This works by:
     * 1. Getting the source NativeImage which contains all frames as a vertical strip
     * 2. For each animation frame, copy that frame's pixels to the frame 0 position
     * 3. Render the item (which will now show the modified frame)
     * 4. Restore frame 0's original pixels
     *
     * This is the most accurate method because it uses the actual rendering pipeline
     * with the correct perspective transforms, lighting, and compositing.
     *
     * @param stack The item stack to render
     * @return AnimationSequence with rendered frames
     */
    public AnimationSequence renderAnimationSequenceDirectComposite(ItemStack stack) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty()) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Get ALL animated sprites for this item
        List<TextureAtlasSprite> animatedSprites = getAllAnimatedSprites(stack);
        if (animatedSprites.isEmpty()) {
            LOGGER.info("RecipeFlow DirectComposite: No animated sprites found for {}", stack.getItem());
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Find the sprite with the most frames to determine animation length
        TextureAtlasSprite primarySprite = null;
        int maxFrameCount = 0;
        com.mojang.blaze3d.platform.NativeImage primarySourceImage = null;
        SpriteAnimationMetadata.FrameTimingInfo primaryTiming = null;

        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            if (timing.isAnimated() && timing.frameCount() > maxFrameCount) {
                com.mojang.blaze3d.platform.NativeImage sourceImage = ObfuscationHelper.getOriginalImage(sprite.contents());
                if (sourceImage != null) {
                    maxFrameCount = timing.frameCount();
                    primarySprite = sprite;
                    primarySourceImage = sourceImage;
                    primaryTiming = timing;
                }
            }
        }

        if (primarySprite == null || primarySourceImage == null) {
            LOGGER.warn("RecipeFlow DirectComposite: Could not get source image from any sprite for {}",
                    stack.getItem());
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        int spriteWidth = primarySprite.contents().width();
        int spriteHeight = primarySprite.contents().height();

        LOGGER.info("RecipeFlow DirectComposite: Primary sprite {} has {} frames ({}x{}), source image {}x{}",
                primarySprite.contents().name(), maxFrameCount, spriteWidth, spriteHeight,
                primarySourceImage.getWidth(), primarySourceImage.getHeight());

        // Collect source images for all animated sprites
        // The source image contains all frames stacked vertically
        List<com.mojang.blaze3d.platform.NativeImage> sourceImages = new ArrayList<>();
        List<Integer> spriteWidths = new ArrayList<>();
        List<Integer> spriteHeights = new ArrayList<>();

        for (TextureAtlasSprite sprite : animatedSprites) {
            com.mojang.blaze3d.platform.NativeImage sourceImage = ObfuscationHelper.getOriginalImage(sprite.contents());

            if (sourceImage == null) {
                LOGGER.warn("RecipeFlow DirectComposite: No source image for sprite {}", sprite.contents().name());
                continue;
            }

            int w = sprite.contents().width();
            int h = sprite.contents().height();

            sourceImages.add(sourceImage);
            spriteWidths.add(w);
            spriteHeights.add(h);

            LOGGER.info("RecipeFlow DirectComposite: Sprite {} - sourceImage {}x{}, sprite dims {}x{}",
                    sprite.contents().name(), sourceImage.getWidth(), sourceImage.getHeight(), w, h);
        }

        LOGGER.info("RecipeFlow DirectComposite: Prepared {} sprites for frame swapping", sourceImages.size());

        // Create debug output folder named after the item
        String itemName = stack.getItem().toString().replaceAll("[:/]", "_");
        java.io.File debugDir = new java.io.File("recipeflow_debug", itemName);
        debugDir.mkdirs();
        LOGGER.info("RecipeFlow DirectComposite: Debug output folder: {}", debugDir.getAbsolutePath());

        // Debug: save full source images (vertical strips containing all frames) for visual inspection
        try {
            for (int i = 0; i < animatedSprites.size(); i++) {
                if (i >= sourceImages.size()) continue;
                TextureAtlasSprite sprite = animatedSprites.get(i);
                com.mojang.blaze3d.platform.NativeImage sourceImage = sourceImages.get(i);
                BufferedImage fullStrip = DirectFrameExtractor.nativeImageToBufferedImage(sourceImage);
                if (fullStrip != null) {
                    String spriteName = sprite.contents().name().toString().replaceAll("[:/]", "_");
                    java.io.File outFile = new java.io.File(debugDir, "source_" + spriteName + "_fullstrip.png");
                    javax.imageio.ImageIO.write(fullStrip, "PNG", outFile);
                    LOGGER.info("RecipeFlow DirectComposite: Saved full source strip to {}", outFile.getAbsolutePath());
                }
            }
        } catch (Exception debugEx) {
            LOGGER.warn("RecipeFlow DirectComposite: Failed to save debug strips: {}", debugEx.getMessage());
        }

        // Use timing from primary sprite
        List<Integer> durations = primaryTiming.frameDurationsMs();
        List<Integer> frameIndices = primaryTiming.frameIndices();

        // Get the texture atlas for uploading
        var textureManager = minecraft.getTextureManager();
        var blockAtlasTexture = textureManager.getTexture(InventoryMenu.BLOCK_ATLAS);
        int atlasTextureId = blockAtlasTexture != null ? blockAtlasTexture.getId() : -1;

        LOGGER.info("RecipeFlow DirectComposite: Atlas texture ID = {}, atlas class = {}",
                atlasTextureId, blockAtlasTexture != null ? blockAtlasTexture.getClass().getName() : "null");

        // For each frame in the animation sequence
        for (int seqIdx = 0; seqIdx < maxFrameCount; seqIdx++) {
            int frameIndex = seqIdx < frameIndices.size() ? frameIndices.get(seqIdx) : seqIdx;

            // For each sprite, upload frame N directly from the source image to the atlas
            // The source image has all frames stacked vertically, so we upload from y offset
            for (int i = 0; i < animatedSprites.size(); i++) {
                if (i >= sourceImages.size()) continue;

                TextureAtlasSprite sprite = animatedSprites.get(i);
                com.mojang.blaze3d.platform.NativeImage sourceImage = sourceImages.get(i);
                int w = spriteWidths.get(i);
                int h = spriteHeights.get(i);

                // Calculate y offset for this frame in the vertical strip
                int srcYOffset = frameIndex * h;

                // Upload directly from the source image at the frame's y offset
                // to the atlas at the sprite's position
                // NativeImage.upload(mipLevel, atlasX, atlasY, srcX, srcY, width, height, blur, clamp)
                if (atlasTextureId != -1 && srcYOffset + h <= sourceImage.getHeight()) {
                    RenderSystem.bindTexture(atlasTextureId);
                    sourceImage.upload(0, sprite.getX(), sprite.getY(), 0, srcYOffset, w, h, false, false);

                    // Debug: check corner pixels from this frame to verify frames are different
                    int debugPixelTopLeft = sourceImage.getPixelRGBA(0, srcYOffset);
                    int debugPixelBottomRight = sourceImage.getPixelRGBA(w-1, srcYOffset + h - 1);
                    int debugPixelCenter = sourceImage.getPixelRGBA(w/2, srcYOffset + h/2);

                    if (seqIdx == 0 || seqIdx == 1 || seqIdx == 2) {
                        LOGGER.info("RecipeFlow DirectComposite: Frame {} - uploading from sourceImage y={} to atlas ({}, {}) for {}",
                                frameIndex, srcYOffset, sprite.getX(), sprite.getY(), sprite.contents().name());
                        LOGGER.info("RecipeFlow DirectComposite: Frame {} pixels - TL=0x{}, BR=0x{}, C=0x{}",
                                frameIndex, Integer.toHexString(debugPixelTopLeft),
                                Integer.toHexString(debugPixelBottomRight), Integer.toHexString(debugPixelCenter));

                        // Save extracted frame as PNG for visual debugging
                        try {
                            BufferedImage frameImg = DirectFrameExtractor.extractRegion(sourceImage, 0, srcYOffset, w, h);
                            if (frameImg != null) {
                                String spriteName = sprite.contents().name().toString().replaceAll("[:/]", "_");
                                java.io.File outFile = new java.io.File(debugDir, "extracted_" + spriteName + "_frame" + frameIndex + ".png");
                                javax.imageio.ImageIO.write(frameImg, "PNG", outFile);
                                LOGGER.info("RecipeFlow DirectComposite: Saved debug frame to {}", outFile.getAbsolutePath());
                            }
                        } catch (Exception debugEx) {
                            LOGGER.warn("RecipeFlow DirectComposite: Failed to save debug frame: {}", debugEx.getMessage());
                        }
                    }
                }
            }

            // Force GPU to process the texture upload before rendering
            GL11.glFlush();
            GL11.glFinish();

            // Unbind texture to force re-binding during render
            RenderSystem.bindTexture(0);

            // Now render the item - the atlas now has frame N's pixels at each sprite's location
            BufferedImage renderedFrame = renderItem(stack);
            sequenceFrames.add(renderedFrame);
            frameDurations.add(seqIdx < durations.size() ? durations.get(seqIdx) : 100);

            // Debug: save each rendered frame (the 3D block render) for comparison
            if (seqIdx < 4) {
                try {
                    java.io.File outFile = new java.io.File(debugDir, "rendered_frame" + seqIdx + ".png");
                    javax.imageio.ImageIO.write(renderedFrame, "PNG", outFile);
                    LOGGER.info("RecipeFlow DirectComposite: Saved rendered frame {} to {}", seqIdx, outFile.getAbsolutePath());
                } catch (Exception debugEx) {
                    LOGGER.warn("RecipeFlow DirectComposite: Failed to save rendered frame: {}", debugEx.getMessage());
                }
            }

            LOGGER.debug("RecipeFlow DirectComposite: Rendered frame {} (index {}) for {}",
                    seqIdx, frameIndex, stack.getItem());
        }

        // Restore original frame 0 to the atlas (upload from y=0 of source image)
        for (int i = 0; i < sourceImages.size(); i++) {
            if (i >= animatedSprites.size()) continue;

            TextureAtlasSprite sprite = animatedSprites.get(i);
            com.mojang.blaze3d.platform.NativeImage sourceImage = sourceImages.get(i);
            int w = spriteWidths.get(i);
            int h = spriteHeights.get(i);

            // Re-upload frame 0 (from y=0) to the atlas
            if (atlasTextureId != -1) {
                RenderSystem.bindTexture(atlasTextureId);
                sourceImage.upload(0, sprite.getX(), sprite.getY(), 0, 0, w, h, false, false);
            }
        }

        LOGGER.info("RecipeFlow DirectComposite: Created {} rendered frames for {}, restored original pixels and atlas",
                sequenceFrames.size(), stack.getItem());

        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Animation rendering by uploading frames to texture atlas and re-rendering.
     *
     * With SimpleRenderMode enabled in GTCEu, the emissive quads use normal UV coordinates
     * instead of runtime-recalculated ones. This means uploadFrame() now works correctly -
     * we upload each frame to the atlas, render the item, and Minecraft applies all the
     * correct tinting and 3D positioning.
     *
     * @param stack The item stack to render
     * @return AnimationSequence with rendered frames
     */
    public AnimationSequence renderAnimationSequencePixelReplace(ItemStack stack) {
        List<BufferedImage> sequenceFrames = new ArrayList<>();
        List<Integer> frameDurations = new ArrayList<>();

        if (stack.isEmpty()) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Check if this is an OPV/MAX tier machine with animated tint
        boolean hasAnimatedTint = isGTCEuLoaded() && GTCEuIconHelper.hasAnimatedTint(stack);

        // Get animated sprites
        List<TextureAtlasSprite> animatedSprites = getAllAnimatedSprites(stack);

        // For non-animated sprites with animated tint, we still need to capture the color cycle
        if (animatedSprites.isEmpty() && !hasAnimatedTint) {
            LOGGER.info("RecipeFlow UploadFrame: No animated sprites found for {}", stack.getItem());
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Find the longest sprite animation (most frames) for accurate timing
        // This ensures we capture the full animation cycle of complex textures like MAX tier casings
        // which have 50+ frame animations with varying timings
        TextureAtlasSprite primarySprite = null;
        int spriteFrameCount = 1;
        SpriteAnimationMetadata.FrameTimingInfo primaryTiming = null;
        int longestCycleDurationMs = 0;
        int mostFrames = 0;

        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            if (timing.isAnimated()) {
                int cycleDurationMs = timing.frameDurationsMs().stream().mapToInt(Integer::intValue).sum();
                int frameCount = timing.frameCount();
                // Prefer the animation with the most frames (longest/most complex animation)
                // This ensures we capture the full cycle of textures like MAX tier casings
                if (frameCount > mostFrames || (frameCount == mostFrames && cycleDurationMs > longestCycleDurationMs)) {
                    longestCycleDurationMs = cycleDurationMs;
                    mostFrames = frameCount;
                    spriteFrameCount = frameCount;
                    primarySprite = sprite;
                    primaryTiming = timing;
                }
            }
        }

        // Determine total frame count considering both sprite animation AND tint cycling
        // IMPORTANT: We need to render at a rate that captures ALL sprite animations,
        // not just the primary sprite. The overlay may cycle much faster than the casing.
        int totalFrameCount;
        List<Integer> perFrameDurations = new ArrayList<>(); // Actual timing for each frame

        // Find the minimum frame duration across ALL animated sprites
        // This ensures faster-cycling sprites (like overlays) are captured properly
        int minFrameDurationMs = 100; // Default minimum
        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            for (int duration : timing.frameDurationsMs()) {
                if (duration > 0 && duration < minFrameDurationMs) {
                    minFrameDurationMs = duration;
                }
            }
        }
        // Clamp to reasonable range (50ms minimum to avoid too many frames)
        minFrameDurationMs = Math.max(50, minFrameDurationMs);

        // Calculate total animation duration (use longest cycle or LCM for proper looping)
        int totalDurationMs = 0;
        if (primaryTiming != null) {
            totalDurationMs = primaryTiming.totalDurationMs();
        }
        // Also consider other sprites - we want the full animation to loop properly
        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            if (timing.totalDurationMs() > totalDurationMs) {
                totalDurationMs = timing.totalDurationMs();
            }
        }
        if (totalDurationMs <= 0) {
            totalDurationMs = 2400; // Default 2.4 seconds
        }

        if (hasAnimatedTint) {
            if (spriteFrameCount > 1 && primaryTiming != null) {
                // We have animated sprites with animated tint
                // Render at minimum frame duration to capture all sprite cycles
                totalFrameCount = totalDurationMs / minFrameDurationMs;
                for (int i = 0; i < totalFrameCount; i++) {
                    perFrameDurations.add(minFrameDurationMs);
                }

                LOGGER.info("RecipeFlow UploadFrame: {} has animated tint + sprites - rendering {} frames at {}ms each (total {}ms)",
                        stack.getItem(), totalFrameCount, minFrameDurationMs, totalDurationMs);
            } else {
                // No animated sprites, but we have animated tint (pure color cycling)
                // Use 100ms per frame for 24 frames = 2.4 seconds
                int targetFrameCount = 24;
                totalFrameCount = targetFrameCount;
                int frameDurationMs = 100;

                for (int i = 0; i < totalFrameCount; i++) {
                    perFrameDurations.add(frameDurationMs);
                }

                LOGGER.info("RecipeFlow UploadFrame: {} has animated tint only - {} frames at {}ms each for color cycle",
                        stack.getItem(), totalFrameCount, frameDurationMs);
            }
        } else {
            // No animated tint - render at minimum frame duration to capture all sprite cycles
            totalFrameCount = totalDurationMs / minFrameDurationMs;
            for (int i = 0; i < totalFrameCount; i++) {
                perFrameDurations.add(minFrameDurationMs);
            }

            LOGGER.info("RecipeFlow UploadFrame: {} sprites only - rendering {} frames at {}ms each (total {}ms)",
                    stack.getItem(), totalFrameCount, minFrameDurationMs, totalDurationMs);
        }

        if (totalFrameCount <= 1 && !hasAnimatedTint) {
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        LOGGER.info("RecipeFlow UploadFrame: Rendering {} frames for {} with {} animated sprites, tintCycle={}",
                totalFrameCount, stack.getItem(), animatedSprites.size(), hasAnimatedTint);

        // Prepare animation info for all sprites (get uploadFrame method handles)
        List<SpriteAnimationInfo> spriteInfos = new ArrayList<>();
        for (TextureAtlasSprite sprite : animatedSprites) {
            SpriteAnimationInfo info = prepareSpritAnimationInfo(sprite);
            if (info != null) {
                spriteInfos.add(info);
                LOGGER.debug("RecipeFlow UploadFrame: Prepared sprite {} at ({},{}) with {} frames",
                        sprite.contents().name(), info.atlasX, info.atlasY, info.frameCount);
            }
        }

        // Get the texture atlas
        var textureManager = minecraft.getTextureManager();
        var blockAtlas = textureManager.getTexture(InventoryMenu.BLOCK_ATLAS);

        if (blockAtlas == null && !spriteInfos.isEmpty()) {
            LOGGER.warn("RecipeFlow UploadFrame: Could not get block atlas texture");
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
            return new AnimationSequence(sequenceFrames, frameDurations);
        }

        // Ensure SimpleRenderMode is enabled for proper UV handling
        boolean wasSimpleRenderModeEnabled = SimpleRenderModeHelper.isEnabled();
        if (!wasSimpleRenderModeEnabled) {
            SimpleRenderModeHelper.setModeIncludeEmissive();
        }

        // Save original CLIENT_TIME if we're doing tint cycling
        long originalClientTime = hasAnimatedTint ? GTCEuIconHelper.getClientTime() : -1;

        // Track cumulative time for CLIENT_TIME advancement
        long cumulativeTimeMs = 0;

        try {
            // For each frame, upload to atlas and render
            for (int frameIdx = 0; frameIdx < totalFrameCount; frameIdx++) {
                try {
                    // If we have animated tint, spread a PARTIAL color cycle across the GIF
                    // A full rainbow is 288 ticks (14.4s), but that's too much color change.
                    // Instead, we use 1/4 of the rainbow (72 ticks = 90 degrees of hue)
                    // This gives a subtle, gradual color shift that loops smoothly.
                    if (hasAnimatedTint && originalClientTime >= 0) {
                        // Use 1/4 of the full rainbow for subtle color shift
                        // 72 ticks = 90 degrees of hue change (red->yellow or blue->purple, etc.)
                        int colorCycleTicks = 72; // 1/4 of 288
                        double progress = (double) frameIdx / totalFrameCount;
                        long ticksOffset = (long) (progress * colorCycleTicks);
                        long newTime = originalClientTime + ticksOffset;
                        GTCEuIconHelper.setClientTime(newTime);
                    }

                    // Bind the block atlas texture before uploading frames
                    if (blockAtlas != null && !spriteInfos.isEmpty()) {
                        RenderSystem.bindTexture(blockAtlas.getId());

                        // Upload this frame to ALL animated sprites
                        // Each sprite advances based on elapsed time and its own timing
                        for (SpriteAnimationInfo info : spriteInfos) {
                            // Calculate which frame this sprite should be on based on elapsed time
                            int spriteFrameIndex = calculateSpriteFrameAtTime(info, cumulativeTimeMs);
                            info.uploadFrameMethod.invoke(info.animatedTexture, info.atlasX, info.atlasY, spriteFrameIndex);
                        }
                    }

                    // Now render the full item - the atlas now has this frame's textures
                    // Minecraft will apply proper tinting (including the current CLIENT_TIME color)
                    BufferedImage renderedFrame = renderItem(stack);
                    sequenceFrames.add(renderedFrame);

                    // Get frame duration from the pre-calculated list
                    int duration = (frameIdx < perFrameDurations.size())
                            ? perFrameDurations.get(frameIdx) : 100;
                    frameDurations.add(duration);

                    // Advance cumulative time for next frame's CLIENT_TIME calculation
                    cumulativeTimeMs += duration;

                    if (frameIdx % 10 == 0 || frameIdx == totalFrameCount - 1) {
                        LOGGER.info("RecipeFlow UploadFrame: Rendered frame {}/{} for {} (time={}ms)",
                                frameIdx + 1, totalFrameCount, stack.getItem(), cumulativeTimeMs);
                    }

                } catch (Exception e) {
                    LOGGER.warn("RecipeFlow UploadFrame: Failed to render frame {} for {}: {}",
                            frameIdx, stack.getItem(), e.getMessage());
                }
            }

            // Restore frame 0 for all sprites to leave atlas in a clean state
            if (blockAtlas != null && !spriteInfos.isEmpty()) {
                try {
                    RenderSystem.bindTexture(blockAtlas.getId());
                    for (SpriteAnimationInfo info : spriteInfos) {
                        info.uploadFrameMethod.invoke(info.animatedTexture, info.atlasX, info.atlasY, 0);
                    }
                } catch (Exception ignored) {}
            }

        } finally {
            // Restore SimpleRenderMode state
            if (!wasSimpleRenderModeEnabled) {
                SimpleRenderModeHelper.disable();
            }

            // Restore original CLIENT_TIME
            if (originalClientTime >= 0) {
                GTCEuIconHelper.setClientTime(originalClientTime);
            }
        }

        if (sequenceFrames.isEmpty()) {
            // Safety fallback
            sequenceFrames.add(renderItem(stack));
            frameDurations.add(0);
        }

        LOGGER.info("RecipeFlow UploadFrame: Created {} frames for {}", sequenceFrames.size(), stack.getItem());
        return new AnimationSequence(sequenceFrames, frameDurations);
    }

    /**
     * Find emissive pixels by comparing two renders: one without emissive quads, one with.
     * This provides pixel-perfect detection of which pixels are part of the emissive overlay.
     *
     * @param withoutEmissive Render with EXCLUDE_EMISSIVE mode (no emissive quads)
     * @param withEmissive Render with INCLUDE_EMISSIVE mode (emissive quads included)
     * @return Set of pixel positions that differ between the two renders
     */
    private Set<Long> findEmissivePixelsByComparison(BufferedImage withoutEmissive, BufferedImage withEmissive) {
        Set<Long> emissivePositions = new HashSet<>();
        int width = Math.min(withoutEmissive.getWidth(), withEmissive.getWidth());
        int height = Math.min(withoutEmissive.getHeight(), withEmissive.getHeight());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixelWithout = withoutEmissive.getRGB(x, y);
                int pixelWith = withEmissive.getRGB(x, y);

                // If the pixels differ, this is an emissive pixel
                if (pixelWithout != pixelWith) {
                    emissivePositions.add(((long)x << 32) | (y & 0xFFFFFFFFL));
                }
            }
        }
        return emissivePositions;
    }

    /**
     * Find pixels that are likely part of the emissive overlay using brightness/saturation heuristics.
     * This is the legacy fallback when the Mode API is not available.
     */
    private Set<Long> findEmissivePixelsByBrightness(BufferedImage image) {
        Set<Long> emissivePositions = new HashSet<>();
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a < 128) continue; // Skip mostly transparent

                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // Calculate brightness (simple average)
                float brightness = (r + g + b) / (3.0f * 255.0f);

                // Calculate saturation (difference between max and min channel)
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                float saturation = max > 0 ? (float)(max - min) / max : 0;

                // Emissive pixels are typically bright and/or saturated
                // GTCEu overlays are often cyan/green with high brightness
                if (brightness > 0.4f && saturation > 0.2f) {
                    // Store position as a single long (x in high bits, y in low bits)
                    emissivePositions.add(((long)x << 32) | (y & 0xFFFFFFFFL));
                }
            }
        }
        return emissivePositions;
    }

    /**
     * Compute the average brightness of visible pixels in an image.
     */
    private float computeAverageBrightness(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        float totalBrightness = 0;
        int count = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                int a = (pixel >> 24) & 0xFF;
                if (a < 10) continue; // Skip transparent

                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                totalBrightness += (r + g + b) / (3.0f * 255.0f);
                count++;
            }
        }

        return count > 0 ? totalBrightness / count : 0;
    }

    /**
     * Create a deep copy of a BufferedImage.
     */
    private BufferedImage copyImage(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return copy;
    }

    /**
     * Universal animation rendering with auto-detected frame count.
     * Tries multiple approaches in order of preference:
     * 1. Direct frame extraction (most reliable for custom renderers like GTCEu)
     * 2. GTCEu overlay sprite ticking (renders 3D block with animated textures)
     * 3. Global texture atlas tick method
     *
     * Note: The texture atlas uploadFrame() approach doesn't work for GTCEu because
     * the UV coordinates are baked into quads at model bake time. GTCEu's
     * GTQuadTransformers.setSprite() calculates UVs using sprite.getU0/U1/V0/V1()
     * which always point to frame 0's bounds. Runtime uploadFrame() uploads new
     * pixel data but the UVs don't change.
     *
     * Direct frame extraction bypasses this by reading directly from the source
     * NativeImage which contains all animation frames as a vertical strip.
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

        // For GTCEu items, use PIXEL REPLACEMENT approach
        // This bypasses the texture atlas animation system entirely because GTCEu's
        // runtime UV calculation means uploadFrame() doesn't affect the rendered output.
        // Instead, we render frame 0, then replace pixels that match frame 0's sprite
        // with pixels from subsequent frames.
        if (isGTCEuLoaded() && gtSprites != null && !gtSprites.isEmpty()) {
            LOGGER.info("RecipeFlow: Using PIXEL REPLACEMENT for GTCEu item with {} sprites, {} frames",
                    gtSprites.size(), frameCount);

            // Try pixel replacement first (most reliable for color-transformed textures)
            AnimationSequence pixelResult = renderAnimationSequencePixelReplace(stack);
            if (!pixelResult.isEmpty() && pixelResult.totalFrames() > 1) {
                LOGGER.info("RecipeFlow: Pixel replacement successful - {} frames rendered",
                        pixelResult.totalFrames());
                return pixelResult;
            }

            // Fall back to direct composite (uploads to atlas)
            LOGGER.warn("RecipeFlow: Pixel replacement failed, trying direct composite");
            AnimationSequence compositeResult = renderAnimationSequenceDirectComposite(stack);
            if (!compositeResult.isEmpty() && compositeResult.totalFrames() > 1) {
                LOGGER.info("RecipeFlow: Direct composite successful - {} frames rendered",
                        compositeResult.totalFrames());
                return compositeResult;
            }

            // Fall back to the old sprite ticking method if compositing fails
            LOGGER.warn("RecipeFlow: Direct composite failed or returned single frame, trying sprite ticking");
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
        final List<Integer> frameDurationsMs;  // Per-frame durations in milliseconds
        final List<Integer> frameIndices;      // Actual sprite frame index for each sequence frame
        final int totalCycleDurationMs;        // Total cycle duration

        SpriteAnimationInfo(Object animatedTexture, Method uploadFrameMethod,
                           int atlasX, int atlasY, int frameCount,
                           List<Integer> frameDurationsMs, List<Integer> frameIndices) {
            this.animatedTexture = animatedTexture;
            this.uploadFrameMethod = uploadFrameMethod;
            this.atlasX = atlasX;
            this.atlasY = atlasY;
            this.frameCount = frameCount;
            this.frameDurationsMs = frameDurationsMs;
            this.frameIndices = frameIndices;
            this.totalCycleDurationMs = frameDurationsMs.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Calculate which sprite frame index to upload at a given elapsed time.
     * This properly handles sprites with different timing - each sprite advances
     * according to its own per-frame durations.
     *
     * IMPORTANT: Returns the actual sprite frame index (for uploadFrame), not the
     * sequence frame index. The sequence may have 50 frames that cycle through
     * 10 unique sprite frames with different timings.
     *
     * @param info The sprite animation info with timing data
     * @param elapsedTimeMs The elapsed time in milliseconds since animation start
     * @return The sprite frame index to upload (the actual texture frame, not sequence position)
     */
    private int calculateSpriteFrameAtTime(SpriteAnimationInfo info, long elapsedTimeMs) {
        if (info.frameCount <= 1 || info.totalCycleDurationMs <= 0) {
            return 0;
        }

        // Wrap time to cycle duration
        long timeInCycle = elapsedTimeMs % info.totalCycleDurationMs;

        // Find which sequence frame we're in by accumulating durations
        long accumulated = 0;
        for (int i = 0; i < info.frameDurationsMs.size(); i++) {
            accumulated += info.frameDurationsMs.get(i);
            if (timeInCycle < accumulated) {
                // Return the actual sprite frame index, not the sequence index
                // The sequence may repeat frames (e.g., 0,1,2,3,2,1,0,...) with different timings
                return info.frameIndices.get(i);
            }
        }

        // Fallback to last frame's sprite index (shouldn't happen with correct timing)
        return info.frameIndices.get(info.frameCount - 1);
    }

    /**
     * Prepare animation info for a sprite (find the animatedTexture and uploadFrame method).
     * Also fetches per-frame timing data from the sprite's animation metadata.
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

            // Get frame timing from sprite metadata
            SpriteAnimationMetadata.FrameTimingInfo timing = SpriteAnimationMetadata.getFrameTimings(sprite);
            int frameCount = timing.frameCount();
            List<Integer> frameDurationsMs = timing.frameDurationsMs();
            List<Integer> frameIndices = timing.frameIndices();

            LOGGER.debug("RecipeFlow: Sprite {} has {} frames, cycle={}ms, durations={}",
                    sprite.contents().name(), frameCount, timing.totalDurationMs(), frameDurationsMs);

            return new SpriteAnimationInfo(
                    animatedTexture,
                    uploadFrameMethod,
                    sprite.getX(),
                    sprite.getY(),
                    frameCount,
                    frameDurationsMs,
                    frameIndices
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

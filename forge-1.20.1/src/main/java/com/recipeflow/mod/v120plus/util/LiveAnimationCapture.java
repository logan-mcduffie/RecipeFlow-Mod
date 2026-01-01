package com.recipeflow.mod.v120plus.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures animated item icons by rendering them live over time and recording frames.
 *
 * This approach works by:
 * 1. Identifying items with animated inventory textures (not world animations)
 * 2. Rendering items in a grid while letting Minecraft's animation system tick naturally
 * 3. Capturing frames at regular intervals
 * 4. Detecting animation loops and trimming to create seamless GIFs
 *
 * This bypasses all the GTCEu UV calculation issues because we capture exactly
 * what the GPU renders - the same thing players see in JEI/EMI.
 */
@OnlyIn(Dist.CLIENT)
public class LiveAnimationCapture {

    private static final Logger LOGGER = LogManager.getLogger();

    // Capture settings
    private static final int ICON_SIZE = 64;  // Size per item in pixels
    private static final int CAPTURE_FPS = 20;  // Frames per second to capture
    private static final int MAX_CAPTURE_SECONDS = 5;  // Max recording time
    private static final int MAX_FRAMES = CAPTURE_FPS * MAX_CAPTURE_SECONDS;

    private final Minecraft minecraft;
    private RenderTarget renderTarget;

    public LiveAnimationCapture() {
        this.minecraft = Minecraft.getInstance();
    }

    /**
     * Result of capturing an animated item.
     */
    public record CaptureResult(
            ItemStack stack,
            List<BufferedImage> frames,
            List<Integer> frameDurationsMs,
            int loopStartFrame,
            boolean success,
            String message
    ) {
        public static CaptureResult failure(ItemStack stack, String message) {
            return new CaptureResult(stack, List.of(), List.of(), 0, false, message);
        }

        public static CaptureResult success(ItemStack stack, List<BufferedImage> frames,
                                            List<Integer> durations, int loopStart) {
            return new CaptureResult(stack, frames, durations, loopStart, true, null);
        }

        public int frameCount() {
            return frames.size();
        }

        public int totalDurationMs() {
            return frameDurationsMs.stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Check if an item has an animated INVENTORY texture.
     * This excludes blocks that only animate in-world (like warped stems).
     *
     * @param stack The item to check
     * @return true if the item's inventory icon is animated
     */
    public boolean hasAnimatedInventoryTexture(ItemStack stack) {
        if (stack.isEmpty()) return false;

        try {
            ItemRenderer itemRenderer = minecraft.getItemRenderer();
            BakedModel model = itemRenderer.getModel(stack, null, null, 0);

            // Get all sprites used by the model's quads
            // These are the textures that actually appear in the inventory view
            RandomSource random = minecraft.level != null ?
                    minecraft.level.random : RandomSource.create();

            // Check quads for all faces including null (for general quads)
            for (Direction dir : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(null, dir, random, ModelData.EMPTY, null);
                for (BakedQuad quad : quads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite != null) {
                        long frameCount = sprite.contents().getUniqueFrames().count();
                        if (frameCount > 1) {
                            LOGGER.debug("RecipeFlow LiveCapture: {} has animated inventory texture: {} ({} frames)",
                                    stack.getItem(), sprite.contents().name(), frameCount);
                            return true;
                        }
                    }
                }
            }

            // Also check general quads (null direction)
            List<BakedQuad> generalQuads = model.getQuads(null, null, random, ModelData.EMPTY, null);
            for (BakedQuad quad : generalQuads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite != null) {
                    long frameCount = sprite.contents().getUniqueFrames().count();
                    if (frameCount > 1) {
                        return true;
                    }
                }
            }

            // Also check the particle icon (sometimes used for simple items)
            TextureAtlasSprite particleIcon = model.getParticleIcon(ModelData.EMPTY);
            if (particleIcon != null) {
                long frameCount = particleIcon.contents().getUniqueFrames().count();
                if (frameCount > 1) {
                    return true;
                }
            }

            // Check if this is a GTCEu machine with animated overlay sprites
            // GTCEu uses runtime texture overrides, so animated textures won't appear
            // in the baked model quads - we need to check via GTCEuIconHelper
            if (GTCEuIconHelper.isGTCEuMachine(stack)) {
                List<TextureAtlasSprite> overlaySprites = GTCEuIconHelper.getOverlaySprites(stack);
                if (!overlaySprites.isEmpty()) {
                    LOGGER.debug("RecipeFlow LiveCapture: {} is GTCEu machine with {} animated overlay sprites",
                            stack.getItem(), overlaySprites.size());
                    return true;
                }
                // Even if we couldn't find specific overlays, GTCEu machines often have animations
                // that we can only detect by actually capturing frames
                LOGGER.debug("RecipeFlow LiveCapture: {} is GTCEu machine - will capture to detect animation",
                        stack.getItem());
                return true;
            }

            return false;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow LiveCapture: Error checking animation for {}: {}",
                    stack.getItem(), e.getMessage());
            return false;
        }
    }

    /**
     * Get the maximum animation cycle length in milliseconds for an item.
     * This is used to determine how long to record.
     */
    public int getAnimationCycleLengthMs(ItemStack stack) {
        if (stack.isEmpty()) return 0;

        int maxCycleMs = 0;

        try {
            ItemRenderer itemRenderer = minecraft.getItemRenderer();
            BakedModel model = itemRenderer.getModel(stack, null, null, 0);

            RandomSource random = minecraft.level != null ?
                    minecraft.level.random : RandomSource.create();

            // Check all faces
            for (Direction dir : Direction.values()) {
                List<BakedQuad> quads = model.getQuads(null, dir, random, ModelData.EMPTY, null);
                for (BakedQuad quad : quads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite != null) {
                        SpriteAnimationMetadata.FrameTimingInfo timing =
                                SpriteAnimationMetadata.getFrameTimings(sprite);
                        if (timing.isAnimated()) {
                            maxCycleMs = Math.max(maxCycleMs, timing.totalDurationMs());
                        }
                    }
                }
            }

            // Also check general quads
            List<BakedQuad> generalQuads = model.getQuads(null, null, random, ModelData.EMPTY, null);
            for (BakedQuad quad : generalQuads) {
                TextureAtlasSprite sprite = quad.getSprite();
                if (sprite != null) {
                    SpriteAnimationMetadata.FrameTimingInfo timing =
                            SpriteAnimationMetadata.getFrameTimings(sprite);
                    if (timing.isAnimated()) {
                        maxCycleMs = Math.max(maxCycleMs, timing.totalDurationMs());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow LiveCapture: Error getting cycle length for {}: {}",
                    stack.getItem(), e.getMessage());
        }

        // Default to 2 seconds if we couldn't determine
        return maxCycleMs > 0 ? maxCycleMs : 2000;
    }

    /**
     * Capture animated frames for a single item by rendering it live.
     *
     * @param stack The item to capture
     * @return CaptureResult with frames and timing
     */
    public CaptureResult captureItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return CaptureResult.failure(stack, "Empty stack");
        }

        // Skip animation check if rendering front face (for testing/debugging)
        if (!renderFrontFace && !hasAnimatedInventoryTexture(stack)) {
            return CaptureResult.failure(stack, "Not animated in inventory");
        }

        LOGGER.info("RecipeFlow LiveCapture: Starting capture for {}", stack.getItem());

        // Determine how long to capture (at least one full cycle + buffer)
        int cycleLengthMs = getAnimationCycleLengthMs(stack);
        int captureMs = Math.min(cycleLengthMs * 2 + 500, MAX_CAPTURE_SECONDS * 1000);
        int frameCount = (captureMs * CAPTURE_FPS) / 1000;
        int frameDelayMs = 1000 / CAPTURE_FPS;

        LOGGER.info("RecipeFlow LiveCapture: Cycle={}ms, capturing {}ms ({} frames at {}fps)",
                cycleLengthMs, captureMs, frameCount, CAPTURE_FPS);

        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        try {
            // Set up render target
            ensureRenderTarget(ICON_SIZE);

            long startTime = System.currentTimeMillis();

            // Capture frames over time
            for (int i = 0; i < frameCount && i < MAX_FRAMES; i++) {
                long targetTime = startTime + (i * frameDelayMs);
                long now = System.currentTimeMillis();

                // Wait until it's time for the next frame
                if (now < targetTime) {
                    try {
                        Thread.sleep(targetTime - now);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // Let Minecraft tick to advance animations
                // This is the key - we're capturing live animation state
                tickAnimations();

                // Render and capture
                BufferedImage frame = renderAndCapture(stack);
                frames.add(frame);
                durations.add(frameDelayMs);

                if (i % 10 == 0) {
                    LOGGER.debug("RecipeFlow LiveCapture: Captured frame {}/{}", i + 1, frameCount);
                }
            }

            LOGGER.info("RecipeFlow LiveCapture: Captured {} raw frames for {}",
                    frames.size(), stack.getItem());

            // Detect loop point and trim
            int loopStart = detectLoopPoint(frames);
            if (loopStart > 0 && loopStart < frames.size() - 1) {
                // Trim to just one cycle
                frames = frames.subList(loopStart, frames.size());
                durations = durations.subList(loopStart, durations.size());
                LOGGER.info("RecipeFlow LiveCapture: Trimmed to {} frames (loop detected at {})",
                        frames.size(), loopStart);
            }

            // Remove duplicate frames at the end (for perfect loop)
            removeDuplicateTail(frames, durations);

            return CaptureResult.success(stack, frames, durations, loopStart);

        } catch (Exception e) {
            LOGGER.error("RecipeFlow LiveCapture: Failed to capture {}: {}",
                    stack.getItem(), e.getMessage(), e);
            return CaptureResult.failure(stack, e.getMessage());
        }
    }

    /**
     * Capture multiple items in a batch (more efficient).
     */
    public Map<ItemStack, CaptureResult> captureBatch(List<ItemStack> stacks) {
        Map<ItemStack, CaptureResult> results = new HashMap<>();

        // Filter to only animated items
        List<ItemStack> animatedStacks = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (hasAnimatedInventoryTexture(stack)) {
                animatedStacks.add(stack);
            } else {
                results.put(stack, CaptureResult.failure(stack, "Not animated"));
            }
        }

        LOGGER.info("RecipeFlow LiveCapture: Batch capturing {} animated items (filtered from {})",
                animatedStacks.size(), stacks.size());

        // For now, capture one at a time
        // Future optimization: render grid and slice
        for (ItemStack stack : animatedStacks) {
            results.put(stack, captureItem(stack));
        }

        return results;
    }

    /**
     * Tick the animation system to advance texture animations.
     */
    private void tickAnimations() {
        // The texture atlas animations are ticked by TextureManager.tick()
        // which calls TextureAtlas.cycleAnimationFrames() for each atlas.
        // We need to manually trigger this since we're not in the normal game loop.
        try {
            // Get the block atlas and tick its animations
            net.minecraft.client.renderer.texture.TextureManager texManager = minecraft.getTextureManager();

            // The TextureManager.tick() method ticks all tickable textures
            // This includes the texture atlases which cycle animation frames
            texManager.tick();

            LOGGER.debug("RecipeFlow LiveCapture: Ticked texture animations");
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow LiveCapture: Could not tick animations: {}", e.getMessage());
        }
    }

    /**
     * Render an item and capture the framebuffer.
     */
    private BufferedImage renderAndCapture(ItemStack stack) {
        renderTarget.bindWrite(true);

        // Clear
        RenderSystem.clearColor(0, 0, 0, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT,
                Minecraft.ON_OSX);

        // Set up projection
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(
                new org.joml.Matrix4f().ortho(0, ICON_SIZE, ICON_SIZE, 0, -1000, 1000),
                com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z
        );

        // Render the item
        renderItemInternal(stack);

        // Restore projection
        RenderSystem.restoreProjectionMatrix();

        // Read pixels
        BufferedImage image = readPixels();

        // Unbind
        renderTarget.unbindWrite();
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);

        return image;
    }

    // Whether to render front face directly (flat) instead of isometric 3D
    private boolean renderFrontFace = false;

    /**
     * Set whether to render the front face directly (flat) instead of the standard
     * isometric 3D GUI view. Useful for machines where the animated overlay is on the front.
     */
    public void setRenderFrontFace(boolean frontFace) {
        this.renderFrontFace = frontFace;
    }

    /**
     * Render an item at the center of the current framebuffer.
     */
    private void renderItemInternal(ItemStack stack) {
        PoseStack poseStack = new PoseStack();

        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, null, null, 0);

        // Set up lighting
        if (model.usesBlockLight()) {
            Lighting.setupFor3DItems();
        } else {
            Lighting.setupForFlatItems();
        }

        // Blending
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
        );
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Position and scale
        poseStack.translate(ICON_SIZE / 2.0f, ICON_SIZE / 2.0f, 100.0f);
        float scale = ICON_SIZE / 16.0f;
        poseStack.scale(scale, scale, scale);

        // Apply transform based on render mode
        if (renderFrontFace) {
            // Render front face directly (flat, facing camera)
            // No rotation - just scale to fit
            poseStack.scale(16.0f, -16.0f, 16.0f);
            LOGGER.debug("RecipeFlow LiveCapture: Rendering front face (flat)");
        } else if (model.isGui3d()) {
            // Standard isometric 3D view
            poseStack.mulPose(new org.joml.Quaternionf().rotationX((float) Math.toRadians(180)));
            poseStack.scale(16.0f, -16.0f, -16.0f);
        } else {
            poseStack.scale(16.0f, 16.0f, 16.0f);
        }

        // Render
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        itemRenderer.render(stack, ItemDisplayContext.GUI, false, poseStack, bufferSource,
                15728880, OverlayTexture.NO_OVERLAY, model);
        bufferSource.endBatch();
    }

    /**
     * Read pixels from the current framebuffer.
     */
    private BufferedImage readPixels() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(ICON_SIZE * ICON_SIZE * 4);
        buffer.order(ByteOrder.nativeOrder());

        GL11.glReadPixels(0, 0, ICON_SIZE, ICON_SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);

        // Flip vertically
        for (int y = 0; y < ICON_SIZE; y++) {
            for (int x = 0; x < ICON_SIZE; x++) {
                int srcIndex = ((ICON_SIZE - 1 - y) * ICON_SIZE + x) * 4;
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
     * Detect where the animation loops by comparing frames.
     * Returns the frame index where a repeat of frame 0 is found.
     */
    private int detectLoopPoint(List<BufferedImage> frames) {
        if (frames.size() < 2) return 0;

        BufferedImage first = frames.get(0);

        // Look for a frame that matches the first frame (within tolerance)
        for (int i = 1; i < frames.size(); i++) {
            if (framesMatch(first, frames.get(i), 5)) {
                LOGGER.debug("RecipeFlow LiveCapture: Loop detected at frame {}", i);
                return i;
            }
        }

        return 0; // No loop detected
    }

    /**
     * Check if two frames are visually identical (within tolerance).
     */
    private boolean framesMatch(BufferedImage a, BufferedImage b, int tolerance) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }

        int diffPixels = 0;
        int totalPixels = a.getWidth() * a.getHeight();

        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);

                if (!pixelsMatch(pa, pb, tolerance)) {
                    diffPixels++;
                }
            }
        }

        // Allow up to 1% difference for minor rendering variations
        return diffPixels < (totalPixels * 0.01);
    }

    /**
     * Check if two pixels match within tolerance.
     */
    private boolean pixelsMatch(int a, int b, int tolerance) {
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int aB = a & 0xFF;

        int bA = (b >> 24) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        return Math.abs(aA - bA) <= tolerance &&
               Math.abs(aR - bR) <= tolerance &&
               Math.abs(aG - bG) <= tolerance &&
               Math.abs(aB - bB) <= tolerance;
    }

    /**
     * Remove duplicate frames at the end that match earlier frames.
     */
    private void removeDuplicateTail(List<BufferedImage> frames, List<Integer> durations) {
        if (frames.size() < 2) return;

        // Check if last frame matches first frame (for loop)
        while (frames.size() > 1 && framesMatch(frames.get(0), frames.get(frames.size() - 1), 5)) {
            frames.remove(frames.size() - 1);
            durations.remove(durations.size() - 1);
        }
    }

    /**
     * Ensure render target is set up with the right size.
     */
    private void ensureRenderTarget(int size) {
        if (renderTarget == null || renderTarget.width != size || renderTarget.height != size) {
            if (renderTarget != null) {
                renderTarget.destroyBuffers();
            }
            renderTarget = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            renderTarget.setClearColor(0, 0, 0, 0);
        }
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

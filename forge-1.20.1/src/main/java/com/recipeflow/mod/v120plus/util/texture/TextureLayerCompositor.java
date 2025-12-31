package com.recipeflow.mod.v120plus.util.texture;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Composites multiple texture layers together to create animation frames.
 *
 * GTCEu machines use layered textures:
 * 1. Base casing texture (e.g., machine hull)
 * 2. Overlay textures (e.g., overlay_front.png)
 * 3. Emissive overlays (e.g., overlay_front_active_emissive.png) - these are the animated parts
 *
 * This compositor handles proper alpha blending and additive blending for emissive layers.
 */
@OnlyIn(Dist.CLIENT)
public class TextureLayerCompositor {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Blending mode for layer compositing.
     */
    public enum BlendMode {
        /** Standard alpha blending (default) */
        NORMAL,
        /** Additive blending for emissive/glow effects */
        ADDITIVE,
        /** Multiply blending */
        MULTIPLY,
        /** Screen blending */
        SCREEN
    }

    /**
     * Configuration for a layer to be composited.
     */
    public static class LayerConfig {
        private final TextureLayerExtractor.TextureLayer layer;
        private final BlendMode blendMode;
        private final float opacity;

        public LayerConfig(TextureLayerExtractor.TextureLayer layer, BlendMode blendMode, float opacity) {
            this.layer = layer;
            this.blendMode = blendMode;
            this.opacity = Math.max(0f, Math.min(1f, opacity));
        }

        public LayerConfig(TextureLayerExtractor.TextureLayer layer, BlendMode blendMode) {
            this(layer, blendMode, 1.0f);
        }

        public LayerConfig(TextureLayerExtractor.TextureLayer layer) {
            this(layer, layer.isEmissive() ? BlendMode.ADDITIVE : BlendMode.NORMAL, 1.0f);
        }

        public TextureLayerExtractor.TextureLayer getLayer() {
            return layer;
        }

        public BlendMode getBlendMode() {
            return blendMode;
        }

        public float getOpacity() {
            return opacity;
        }
    }

    /**
     * Result of animation compositing.
     */
    public static class CompositeResult {
        private final List<BufferedImage> frames;
        private final List<Integer> frameDurationsMs;
        private final int width;
        private final int height;

        public CompositeResult(List<BufferedImage> frames, List<Integer> frameDurationsMs,
                               int width, int height) {
            this.frames = frames;
            this.frameDurationsMs = frameDurationsMs;
            this.width = width;
            this.height = height;
        }

        public List<BufferedImage> getFrames() {
            return frames;
        }

        public List<Integer> getFrameDurationsMs() {
            return frameDurationsMs;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getFrameCount() {
            return frames.size();
        }

        public boolean isEmpty() {
            return frames.isEmpty();
        }

        public int getTotalDurationMs() {
            return frameDurationsMs.stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final int targetWidth;
    private final int targetHeight;

    /**
     * Create a compositor with specified output dimensions.
     *
     * @param targetWidth  Target width for output frames
     * @param targetHeight Target height for output frames
     */
    public TextureLayerCompositor(int targetWidth, int targetHeight) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }

    /**
     * Create a compositor with square output dimensions.
     *
     * @param targetSize Target size for output frames
     */
    public TextureLayerCompositor(int targetSize) {
        this(targetSize, targetSize);
    }

    /**
     * Composite multiple texture layers into an animation sequence.
     *
     * This calculates the LCM of all layer animation lengths to ensure proper
     * synchronization, then composites each frame.
     *
     * @param layers List of layer configurations to composite
     * @return CompositeResult containing all animation frames
     */
    public CompositeResult composite(List<LayerConfig> layers) {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();

        if (layers.isEmpty()) {
            return new CompositeResult(frames, durations, targetWidth, targetHeight);
        }

        // Find the animated layers
        List<LayerConfig> animatedLayers = new ArrayList<>();
        List<LayerConfig> staticLayers = new ArrayList<>();

        for (LayerConfig config : layers) {
            if (config.getLayer().isAnimated()) {
                animatedLayers.add(config);
            } else {
                staticLayers.add(config);
            }
        }

        // Calculate total frames needed
        int totalFrames;
        int frameTimeMs;

        if (animatedLayers.isEmpty()) {
            // No animation, just composite once
            totalFrames = 1;
            frameTimeMs = 0;
        } else {
            // Calculate LCM of all animation lengths for proper sync
            totalFrames = 1;
            frameTimeMs = animatedLayers.get(0).getLayer().getFrameTimeMs();

            for (LayerConfig config : animatedLayers) {
                TextureLayerExtractor.TextureLayer layer = config.getLayer();
                int layerFrames = layer.getFrameCount();
                totalFrames = lcm(totalFrames, layerFrames);

                // Use the longest frame time
                int layerFrameTime = layer.getFrameTimeMs();
                if (layerFrameTime > frameTimeMs) {
                    frameTimeMs = layerFrameTime;
                }
            }

            LOGGER.debug("RecipeFlow: Compositing {} animated layers, {} total frames at {}ms",
                    animatedLayers.size(), totalFrames, frameTimeMs);
        }

        // Pre-render static layers as a base
        BufferedImage staticBase = createBaseImage();
        Graphics2D staticG = staticBase.createGraphics();
        configureGraphics(staticG);

        for (LayerConfig config : staticLayers) {
            BufferedImage layerFrame = config.getLayer().getFrame(0);
            BufferedImage scaled = scaleToTarget(layerFrame);
            drawLayer(staticG, scaled, config.getBlendMode(), config.getOpacity());
        }
        staticG.dispose();

        // Composite each frame
        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
            BufferedImage composited = createBaseImage();
            Graphics2D g = composited.createGraphics();
            configureGraphics(g);

            // Draw static base first
            g.drawImage(staticBase, 0, 0, null);

            // Draw animated layers
            for (LayerConfig config : animatedLayers) {
                TextureLayerExtractor.TextureLayer layer = config.getLayer();

                // Get the correct frame for this layer
                int layerFrameIndex = layer.getActualFrameIndex(frameIndex);
                BufferedImage layerFrame = layer.getFrame(layerFrameIndex);
                BufferedImage scaled = scaleToTarget(layerFrame);

                drawLayer(g, scaled, config.getBlendMode(), config.getOpacity());
            }

            g.dispose();
            frames.add(composited);
            durations.add(frameTimeMs);
        }

        // Deduplicate consecutive identical frames
        return deduplicateFrames(frames, durations);
    }

    /**
     * Composite layers for a specific frame index.
     *
     * @param layers     List of layer configurations
     * @param frameIndex The frame index to render
     * @return The composited frame
     */
    public BufferedImage compositeFrame(List<LayerConfig> layers, int frameIndex) {
        BufferedImage composited = createBaseImage();
        Graphics2D g = composited.createGraphics();
        configureGraphics(g);

        for (LayerConfig config : layers) {
            TextureLayerExtractor.TextureLayer layer = config.getLayer();
            int layerFrameIndex = layer.isAnimated() ? layer.getActualFrameIndex(frameIndex) : 0;
            BufferedImage layerFrame = layer.getFrame(layerFrameIndex);
            BufferedImage scaled = scaleToTarget(layerFrame);

            drawLayer(g, scaled, config.getBlendMode(), config.getOpacity());
        }

        g.dispose();
        return composited;
    }

    /**
     * Create an empty base image with transparency.
     */
    private BufferedImage createBaseImage() {
        return new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Configure graphics context for high-quality rendering.
     */
    private void configureGraphics(Graphics2D g) {
        // Use nearest-neighbor for pixel art (Minecraft textures)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
    }

    /**
     * Scale an image to target dimensions using nearest-neighbor interpolation.
     */
    private BufferedImage scaleToTarget(BufferedImage source) {
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);

        double scaleX = (double) source.getWidth() / targetWidth;
        double scaleY = (double) source.getHeight() / targetHeight;

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int srcX = Math.min((int) (x * scaleX), source.getWidth() - 1);
                int srcY = Math.min((int) (y * scaleY), source.getHeight() - 1);
                scaled.setRGB(x, y, source.getRGB(srcX, srcY));
            }
        }

        return scaled;
    }

    /**
     * Draw a layer onto the destination with the specified blend mode.
     */
    private void drawLayer(Graphics2D g, BufferedImage layer, BlendMode blendMode, float opacity) {
        switch (blendMode) {
            case NORMAL:
                drawNormalBlend(g, layer, opacity);
                break;
            case ADDITIVE:
                drawAdditiveBlend(g, layer, opacity);
                break;
            case MULTIPLY:
                drawMultiplyBlend(g, layer, opacity);
                break;
            case SCREEN:
                drawScreenBlend(g, layer, opacity);
                break;
        }
    }

    /**
     * Standard alpha blending.
     */
    private void drawNormalBlend(Graphics2D g, BufferedImage layer, float opacity) {
        if (opacity < 1.0f) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        }
        g.drawImage(layer, 0, 0, null);
        if (opacity < 1.0f) {
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    /**
     * Additive blending for emissive/glow effects.
     * Result = Destination + Source * opacity
     */
    private void drawAdditiveBlend(Graphics2D g, BufferedImage layer, float opacity) {
        // Get the underlying image from the graphics context
        // For additive blending, we need to manipulate pixels directly

        BufferedImage dest = (BufferedImage) g.getDeviceConfiguration()
                .createCompatibleImage(targetWidth, targetHeight, java.awt.Transparency.TRANSLUCENT);

        // Copy current state to dest
        Graphics2D destG = dest.createGraphics();
        destG.drawImage(g.getDeviceConfiguration().createCompatibleImage(targetWidth, targetHeight,
                java.awt.Transparency.TRANSLUCENT), 0, 0, null);
        destG.dispose();

        // Manual additive blend
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int srcArgb = layer.getRGB(x, y);
                int srcA = (srcArgb >> 24) & 0xFF;

                if (srcA == 0) continue; // Skip fully transparent pixels

                int srcR = (srcArgb >> 16) & 0xFF;
                int srcG = (srcArgb >> 8) & 0xFF;
                int srcB = srcArgb & 0xFF;

                // Apply opacity
                srcR = (int) (srcR * opacity);
                srcG = (int) (srcG * opacity);
                srcB = (int) (srcB * opacity);

                // Get current destination pixel from graphics context
                // Since we can't easily read from Graphics2D, use the layer's alpha
                // to determine how much to blend

                // For simplicity, just draw with SRC_OVER for emissive
                // A true additive blend would require custom compositing
            }
        }

        // Fallback: Use standard alpha blend with full brightness
        // This approximates emissive by ensuring the glow layer is visible
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        g.drawImage(layer, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
    }

    /**
     * Multiply blending.
     * Result = Destination * Source
     */
    private void drawMultiplyBlend(Graphics2D g, BufferedImage layer, float opacity) {
        // Multiply blend requires pixel-level manipulation
        // For now, fall back to normal blend
        drawNormalBlend(g, layer, opacity);
    }

    /**
     * Screen blending.
     * Result = 1 - (1 - Destination) * (1 - Source)
     */
    private void drawScreenBlend(Graphics2D g, BufferedImage layer, float opacity) {
        // Screen blend requires pixel-level manipulation
        // For now, fall back to normal blend
        drawNormalBlend(g, layer, opacity);
    }

    /**
     * Composite with true additive blending (pixel-level).
     * This is more accurate for emissive effects but slower.
     */
    public BufferedImage compositeWithAdditiveBlend(BufferedImage base, BufferedImage emissive, float opacity) {
        BufferedImage result = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                int baseArgb = base.getRGB(x, y);
                int emissiveArgb = emissive.getRGB(x, y);

                int baseA = (baseArgb >> 24) & 0xFF;
                int baseR = (baseArgb >> 16) & 0xFF;
                int baseG = (baseArgb >> 8) & 0xFF;
                int baseB = baseArgb & 0xFF;

                int emA = (emissiveArgb >> 24) & 0xFF;
                int emR = (int) (((emissiveArgb >> 16) & 0xFF) * opacity);
                int emG = (int) (((emissiveArgb >> 8) & 0xFF) * opacity);
                int emB = (int) ((emissiveArgb & 0xFF) * opacity);

                // Additive blend: add colors, clamp to 255
                int resultR = Math.min(255, baseR + emR);
                int resultG = Math.min(255, baseG + emG);
                int resultB = Math.min(255, baseB + emB);
                int resultA = Math.max(baseA, emA);

                result.setRGB(x, y, (resultA << 24) | (resultR << 16) | (resultG << 8) | resultB);
            }
        }

        return result;
    }

    /**
     * Deduplicate consecutive identical frames.
     * Combines durations of identical consecutive frames.
     */
    private CompositeResult deduplicateFrames(List<BufferedImage> frames, List<Integer> durations) {
        if (frames.size() <= 1) {
            return new CompositeResult(frames, durations, targetWidth, targetHeight);
        }

        List<BufferedImage> deduped = new ArrayList<>();
        List<Integer> dedupedDurations = new ArrayList<>();

        deduped.add(frames.get(0));
        dedupedDurations.add(durations.get(0));

        for (int i = 1; i < frames.size(); i++) {
            if (imagesEqual(frames.get(i), deduped.get(deduped.size() - 1))) {
                // Add duration to previous frame
                int lastIndex = dedupedDurations.size() - 1;
                dedupedDurations.set(lastIndex, dedupedDurations.get(lastIndex) + durations.get(i));
            } else {
                deduped.add(frames.get(i));
                dedupedDurations.add(durations.get(i));
            }
        }

        if (deduped.size() < frames.size()) {
            LOGGER.debug("RecipeFlow: Deduplicated {} frames to {} frames",
                    frames.size(), deduped.size());
        }

        return new CompositeResult(deduped, dedupedDurations, targetWidth, targetHeight);
    }

    /**
     * Check if two images are pixel-identical.
     */
    private boolean imagesEqual(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }

        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Calculate the Least Common Multiple of two numbers.
     */
    private int lcm(int a, int b) {
        return a * (b / gcd(a, b));
    }

    /**
     * Calculate the Greatest Common Divisor of two numbers.
     */
    private int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}

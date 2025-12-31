package com.recipeflow.mod.v120plus.util;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for reliably extracting animation metadata from Minecraft sprites.
 *
 * This accesses the internal SpriteContents.AnimatedTexture structure which contains:
 * - frames: List<FrameInfo> with per-frame index and timing
 * - frameRowSize: how frames are laid out in the sprite sheet
 * - interpolateFrames: whether to interpolate between frames
 *
 * Each FrameInfo contains:
 * - index: the frame index in the sprite sheet
 * - time: duration in ticks (1 tick = 50ms)
 */
@OnlyIn(Dist.CLIENT)
public class SpriteAnimationMetadata {

    private static final Logger LOGGER = LogManager.getLogger();

    // Cache reflected fields to avoid repeated lookups
    private static Field animatedTextureField = null;
    private static Field framesField = null;
    private static Field frameIndexField = null;
    private static Field frameTimeField = null;
    private static volatile boolean reflectionInitialized = false;
    private static volatile boolean frameFieldsInitialized = false;

    // Cache results per sprite to avoid repeated reflection
    private static final ConcurrentHashMap<String, FrameTimingInfo> cache = new ConcurrentHashMap<>();

    /**
     * Contains complete timing information for an animated sprite.
     */
    public record FrameTimingInfo(
            int frameCount,
            List<Integer> frameDurationsMs,
            List<Integer> frameIndices,
            int totalDurationMs,
            boolean interpolated
    ) {
        /**
         * Get the average frame duration in milliseconds.
         */
        public int averageFrameDurationMs() {
            if (frameCount == 0) return 0;
            return totalDurationMs / frameCount;
        }

        /**
         * Check if this represents an animated sprite.
         */
        public boolean isAnimated() {
            return frameCount > 1;
        }

        /**
         * Create a static (non-animated) timing info.
         */
        public static FrameTimingInfo staticFrame() {
            return new FrameTimingInfo(1, List.of(0), List.of(0), 0, false);
        }

        /**
         * Create a default timing info when metadata cannot be read.
         */
        public static FrameTimingInfo defaultTiming(int frameCount, int defaultFrameTimeMs) {
            List<Integer> durations = Collections.nCopies(frameCount, defaultFrameTimeMs);
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < frameCount; i++) {
                indices.add(i);
            }
            return new FrameTimingInfo(
                    frameCount,
                    durations,
                    indices,
                    frameCount * defaultFrameTimeMs,
                    false
            );
        }
    }

    /**
     * Extract animation metadata from a TextureAtlasSprite.
     * This is the main entry point for getting reliable frame timing information.
     *
     * @param sprite The sprite to extract metadata from
     * @return FrameTimingInfo with per-frame timing, or default values if extraction fails
     */
    public static FrameTimingInfo getFrameTimings(TextureAtlasSprite sprite) {
        if (sprite == null) {
            LOGGER.info("RecipeFlow SpriteAnimationMetadata: getFrameTimings called with null sprite");
            return FrameTimingInfo.staticFrame();
        }

        String cacheKey = sprite.contents().name().toString();
        LOGGER.info("RecipeFlow SpriteAnimationMetadata: getFrameTimings called for {}", cacheKey);

        // Check cache first
        FrameTimingInfo cached = cache.get(cacheKey);
        if (cached != null) {
            LOGGER.info("RecipeFlow SpriteAnimationMetadata: Returning cached result for {}: {} frames, {}ms total",
                    cacheKey, cached.frameCount(), cached.totalDurationMs());
            return cached;
        }

        // Extract and cache
        LOGGER.info("RecipeFlow SpriteAnimationMetadata: No cache hit, extracting for {}", cacheKey);
        FrameTimingInfo result = extractFrameTimings(sprite);
        cache.put(cacheKey, result);

        LOGGER.info("RecipeFlow SpriteAnimationMetadata: Cached result for {}: {} frames, {}ms total",
                cacheKey, result.frameCount(), result.totalDurationMs());
        return result;
    }

    /**
     * Clear the metadata cache. Call this if textures are reloaded.
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * Internal method to extract frame timings via reflection.
     */
    private static FrameTimingInfo extractFrameTimings(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();

        LOGGER.info("RecipeFlow SpriteAnimationMetadata: Extracting frame timings for {}", contents.name());

        // Quick check: if getUniqueFrames returns 1, it's not animated
        long uniqueFrames = contents.getUniqueFrames().count();
        LOGGER.info("RecipeFlow SpriteAnimationMetadata: {} has {} unique frames", contents.name(), uniqueFrames);

        if (uniqueFrames <= 1) {
            LOGGER.info("RecipeFlow SpriteAnimationMetadata: {} is not animated (<=1 frames)", contents.name());
            return FrameTimingInfo.staticFrame();
        }

        // Initialize base reflection if needed
        if (!reflectionInitialized) {
            initializeBaseReflection();
        }

        // If base reflection failed, use fallback
        if (animatedTextureField == null) {
            LOGGER.warn("RecipeFlow SpriteAnimationMetadata: Using fallback for {} ({} frames) - animatedTextureField is null",
                    contents.name(), uniqueFrames);
            return FrameTimingInfo.defaultTiming((int) uniqueFrames, 100);
        }

        try {
            // Get the AnimatedTexture object
            Object animatedTexture = animatedTextureField.get(contents);
            LOGGER.info("RecipeFlow SpriteAnimationMetadata: animatedTexture for {}: {}",
                    contents.name(), animatedTexture != null ? animatedTexture.getClass().getName() : "NULL");

            if (animatedTexture == null) {
                LOGGER.warn("RecipeFlow SpriteAnimationMetadata: {} has no animatedTexture object despite {} unique frames",
                        contents.name(), uniqueFrames);
                return FrameTimingInfo.staticFrame();
            }

            // Initialize frame fields lazily (need actual AnimatedTexture instance)
            if (!frameFieldsInitialized) {
                initializeFrameFields(animatedTexture);
            }

            // If frame field initialization failed, use fallback
            if (framesField == null || frameIndexField == null || frameTimeField == null) {
                LOGGER.warn("RecipeFlow SpriteAnimationMetadata: Using fallback for {} ({} frames) - frame fields not initialized. framesField={}, frameIndexField={}, frameTimeField={}",
                        contents.name(), uniqueFrames,
                        framesField != null ? framesField.getName() : "null",
                        frameIndexField != null ? frameIndexField.getName() : "null",
                        frameTimeField != null ? frameTimeField.getName() : "null");
                return FrameTimingInfo.defaultTiming((int) uniqueFrames, 100);
            }

            // Get the frames list
            List<?> frames = (List<?>) framesField.get(animatedTexture);
            LOGGER.info("RecipeFlow SpriteAnimationMetadata: frames list for {}: {} entries",
                    contents.name(), frames != null ? frames.size() : "NULL");

            if (frames == null || frames.isEmpty()) {
                LOGGER.warn("RecipeFlow SpriteAnimationMetadata: {} has empty/null frames list", contents.name());
                return FrameTimingInfo.defaultTiming((int) uniqueFrames, 100);
            }

            // Check for interpolation
            boolean interpolated = false;
            try {
                Field interpolateField = ObfuscationHelper.findField(animatedTexture.getClass(),
                        ObfuscationHelper.ANIMATED_TEXTURE_INTERPOLATE);
                if (interpolateField != null) {
                    interpolated = interpolateField.getBoolean(animatedTexture);
                }
            } catch (Exception e) {
                // Interpolation field not found, default to false
            }

            // Extract per-frame data
            List<Integer> frameDurationsMs = new ArrayList<>();
            List<Integer> frameIndices = new ArrayList<>();
            int totalMs = 0;

            LOGGER.info("RecipeFlow SpriteAnimationMetadata: Extracting {} frames for {}", frames.size(), contents.name());

            for (int i = 0; i < frames.size(); i++) {
                Object frameInfo = frames.get(i);
                int index = frameIndexField.getInt(frameInfo);
                int timeTicks = frameTimeField.getInt(frameInfo);
                int timeMs = timeTicks * 50; // 1 tick = 50ms

                frameIndices.add(index);
                frameDurationsMs.add(timeMs);
                totalMs += timeMs;

                LOGGER.info("RecipeFlow SpriteAnimationMetadata: {} frame {}: index={}, timeTicks={}, timeMs={}",
                        contents.name(), i, index, timeTicks, timeMs);
            }

            FrameTimingInfo result = new FrameTimingInfo(
                    frames.size(),
                    frameDurationsMs,
                    frameIndices,
                    totalMs,
                    interpolated
            );

            LOGGER.info("RecipeFlow SpriteAnimationMetadata: SUCCESS {} -> {} frames, total {}ms, avgMs={}, interpolated={}",
                    contents.name(), result.frameCount(), result.totalDurationMs(), result.averageFrameDurationMs(), result.interpolated());

            return result;

        } catch (Exception e) {
            LOGGER.error("RecipeFlow SpriteAnimationMetadata: EXCEPTION extracting metadata for {}: {}",
                    contents.name(), e.getMessage(), e);
            return FrameTimingInfo.defaultTiming((int) uniqueFrames, 100);
        }
    }

    /**
     * Initialize the animatedTexture field on SpriteContents.
     */
    private static synchronized void initializeBaseReflection() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;

        try {
            // Use ObfuscationHelper's cached field getter (uses Forge's ObfuscationReflectionHelper)
            animatedTextureField = ObfuscationHelper.getSpriteContentsAnimatedTextureField();

            if (animatedTextureField == null) {
                LOGGER.error("RecipeFlow SpriteAnimationMetadata: Could not find animatedTexture field on SpriteContents");
                LOGGER.debug("RecipeFlow SpriteAnimationMetadata: Available fields: {}",
                        java.util.Arrays.toString(SpriteContents.class.getDeclaredFields()));
                return;
            }

            LOGGER.info("RecipeFlow SpriteAnimationMetadata: Base reflection initialized, animatedTexture field: {}",
                    animatedTextureField.getName());

        } catch (Exception e) {
            LOGGER.error("RecipeFlow SpriteAnimationMetadata: Failed to initialize base reflection: {}", e.getMessage());
        }
    }

    /**
     * Initialize fields on AnimatedTexture and FrameInfo classes.
     * Must be called with an actual AnimatedTexture instance.
     */
    private static synchronized void initializeFrameFields(Object animatedTexture) {
        if (frameFieldsInitialized) {
            return;
        }
        frameFieldsInitialized = true;

        try {
            Class<?> animatedTextureClass = animatedTexture.getClass();

            LOGGER.debug("RecipeFlow SpriteAnimationMetadata: AnimatedTexture class: {}", animatedTextureClass.getName());
            LOGGER.debug("RecipeFlow SpriteAnimationMetadata: AnimatedTexture fields: {}",
                    java.util.Arrays.toString(animatedTextureClass.getDeclaredFields()));

            // Find frames field (List<FrameInfo>) using ObfuscationHelper constants
            framesField = ObfuscationHelper.findField(animatedTextureClass,
                    ObfuscationHelper.ANIMATED_TEXTURE_FRAMES);
            if (framesField == null) {
                // Try to find any List field as fallback
                for (Field f : animatedTextureClass.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        framesField = f;
                        framesField.setAccessible(true);
                        LOGGER.debug("RecipeFlow SpriteAnimationMetadata: Found frames field by type: {}", f.getName());
                        break;
                    }
                }
            }

            if (framesField == null) {
                LOGGER.error("RecipeFlow SpriteAnimationMetadata: Could not find frames field on AnimatedTexture");
                return;
            }

            // Get an actual FrameInfo instance to find its fields
            List<?> frames = (List<?>) framesField.get(animatedTexture);
            if (frames == null || frames.isEmpty()) {
                LOGGER.error("RecipeFlow SpriteAnimationMetadata: Frames list is empty, cannot initialize FrameInfo fields");
                return;
            }

            Object sampleFrame = frames.get(0);
            Class<?> frameInfoClass = sampleFrame.getClass();

            LOGGER.debug("RecipeFlow SpriteAnimationMetadata: FrameInfo class: {}", frameInfoClass.getName());
            LOGGER.debug("RecipeFlow SpriteAnimationMetadata: FrameInfo fields: {}",
                    java.util.Arrays.toString(frameInfoClass.getDeclaredFields()));

            // Find index and time fields using ObfuscationHelper constants
            frameIndexField = ObfuscationHelper.findField(frameInfoClass, ObfuscationHelper.FRAME_INFO_INDEX);
            frameTimeField = ObfuscationHelper.findField(frameInfoClass, ObfuscationHelper.FRAME_INFO_TIME);

            // If named fields not found, try to find by type (both are int)
            if (frameIndexField == null || frameTimeField == null) {
                List<Field> intFields = new ArrayList<>();
                for (Field f : frameInfoClass.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        intFields.add(f);
                    }
                }

                if (intFields.size() >= 2) {
                    // First int is typically index, second is time
                    frameIndexField = intFields.get(0);
                    frameTimeField = intFields.get(1);
                    LOGGER.debug("RecipeFlow SpriteAnimationMetadata: Found frame fields by type: index={}, time={}",
                            frameIndexField.getName(), frameTimeField.getName());
                }
            }

            if (frameIndexField == null || frameTimeField == null) {
                LOGGER.error("RecipeFlow SpriteAnimationMetadata: Could not find index/time fields on FrameInfo");
                return;
            }

            LOGGER.info("RecipeFlow SpriteAnimationMetadata: Frame fields initialized successfully - index={}, time={}",
                    frameIndexField.getName(), frameTimeField.getName());

        } catch (Exception e) {
            LOGGER.error("RecipeFlow SpriteAnimationMetadata: Failed to initialize frame fields: {}", e.getMessage());
        }
    }
}

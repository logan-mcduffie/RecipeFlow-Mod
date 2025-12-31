package com.recipeflow.mod.v120plus.util.texture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Minecraft .mcmeta animation metadata files.
 *
 * The .mcmeta format defines animation properties for texture sprite sheets:
 * <pre>
 * {
 *   "animation": {
 *     "frametime": 2,              // Default ticks per frame (default: 1)
 *     "interpolate": false,        // Whether to interpolate between frames
 *     "width": 16,                 // Frame width (usually inferred)
 *     "height": 16,                // Frame height (usually inferred)
 *     "frames": [                  // Custom frame order/timing (optional)
 *       0,                         // Frame index with default timing
 *       {"index": 1, "time": 4},   // Frame index with custom timing
 *       2, 3, 4, 5
 *     ]
 *   }
 * }
 * </pre>
 */
@OnlyIn(Dist.CLIENT)
public class McmetaParser {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Represents a single frame in an animation sequence.
     */
    public static class FrameInfo {
        private final int index;
        private final int time; // In ticks, -1 means use default

        public FrameInfo(int index, int time) {
            this.index = index;
            this.time = time;
        }

        public int getIndex() {
            return index;
        }

        public int getTime() {
            return time;
        }

        public boolean hasCustomTime() {
            return time >= 0;
        }
    }

    /**
     * Represents the complete animation metadata from a .mcmeta file.
     */
    public static class AnimationMetadata {
        private final int defaultFrameTime;  // In ticks
        private final boolean interpolate;
        private final int width;
        private final int height;
        private final List<FrameInfo> frames;
        private final int frameCount;

        public AnimationMetadata(int defaultFrameTime, boolean interpolate,
                                  int width, int height, List<FrameInfo> frames, int frameCount) {
            this.defaultFrameTime = defaultFrameTime;
            this.interpolate = interpolate;
            this.width = width;
            this.height = height;
            this.frames = frames;
            this.frameCount = frameCount;
        }

        /**
         * Get the default frame time in ticks.
         */
        public int getDefaultFrameTime() {
            return defaultFrameTime;
        }

        /**
         * Get the default frame time in milliseconds.
         * 1 tick = 50ms (20 ticks per second)
         */
        public int getDefaultFrameTimeMs() {
            return defaultFrameTime * 50;
        }

        /**
         * Get the frame time in milliseconds for a specific frame.
         */
        public int getFrameTimeMs(int frameIndex) {
            if (frames != null && frameIndex >= 0 && frameIndex < frames.size()) {
                FrameInfo info = frames.get(frameIndex);
                if (info.hasCustomTime()) {
                    return info.getTime() * 50;
                }
            }
            return getDefaultFrameTimeMs();
        }

        /**
         * Whether to interpolate between frames.
         */
        public boolean isInterpolate() {
            return interpolate;
        }

        /**
         * Get the frame width (may be -1 if not specified).
         */
        public int getWidth() {
            return width;
        }

        /**
         * Get the frame height (may be -1 if not specified).
         */
        public int getHeight() {
            return height;
        }

        /**
         * Get the list of frames in playback order.
         * Returns null if using default sequential order.
         */
        public List<FrameInfo> getFrames() {
            return frames;
        }

        /**
         * Get the number of unique frames in the sprite sheet.
         */
        public int frameCount() {
            return frameCount;
        }

        /**
         * Check if this animation has custom frame ordering.
         */
        public boolean hasCustomFrameOrder() {
            return frames != null && !frames.isEmpty();
        }

        /**
         * Get the total number of frames in the playback sequence.
         * This may differ from frameCount if frames repeat.
         */
        public int getTotalPlaybackFrames() {
            if (frames != null && !frames.isEmpty()) {
                return frames.size();
            }
            return frameCount;
        }

        /**
         * Get the frame index for a given playback position.
         */
        public int getFrameIndex(int playbackPosition) {
            if (frames != null && !frames.isEmpty()) {
                int pos = playbackPosition % frames.size();
                return frames.get(pos).getIndex();
            }
            return playbackPosition % Math.max(1, frameCount);
        }

        /**
         * Get the total animation duration in milliseconds.
         */
        public int getTotalDurationMs() {
            if (frames != null && !frames.isEmpty()) {
                int total = 0;
                for (FrameInfo frame : frames) {
                    total += frame.hasCustomTime() ? frame.getTime() * 50 : getDefaultFrameTimeMs();
                }
                return total;
            }
            return frameCount * getDefaultFrameTimeMs();
        }
    }

    /**
     * Parse animation metadata from a .mcmeta file input stream.
     *
     * @param inputStream The input stream to read from
     * @return AnimationMetadata or null if parsing fails
     */
    public static AnimationMetadata parse(InputStream inputStream) {
        try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);

            if (!rootElement.isJsonObject()) {
                LOGGER.debug("RecipeFlow: mcmeta root is not a JSON object");
                return null;
            }

            JsonObject root = rootElement.getAsJsonObject();
            JsonElement animationElement = root.get("animation");

            if (animationElement == null || !animationElement.isJsonObject()) {
                LOGGER.debug("RecipeFlow: mcmeta has no animation section");
                return null;
            }

            JsonObject animation = animationElement.getAsJsonObject();

            // Parse frametime (default: 1 tick)
            int frameTime = 1;
            if (animation.has("frametime")) {
                frameTime = animation.get("frametime").getAsInt();
            }

            // Parse interpolate flag
            boolean interpolate = false;
            if (animation.has("interpolate")) {
                interpolate = animation.get("interpolate").getAsBoolean();
            }

            // Parse width/height (optional, usually inferred from texture)
            int width = -1;
            int height = -1;
            if (animation.has("width")) {
                width = animation.get("width").getAsInt();
            }
            if (animation.has("height")) {
                height = animation.get("height").getAsInt();
            }

            // Parse frames array (optional)
            List<FrameInfo> frames = null;
            int maxFrameIndex = -1;

            if (animation.has("frames")) {
                JsonArray framesArray = animation.getAsJsonArray("frames");
                frames = new ArrayList<>();

                for (JsonElement frameElement : framesArray) {
                    if (frameElement.isJsonPrimitive()) {
                        // Simple frame index
                        int index = frameElement.getAsInt();
                        frames.add(new FrameInfo(index, -1));
                        maxFrameIndex = Math.max(maxFrameIndex, index);
                    } else if (frameElement.isJsonObject()) {
                        // Frame with custom timing
                        JsonObject frameObj = frameElement.getAsJsonObject();
                        int index = frameObj.get("index").getAsInt();
                        int time = frameObj.has("time") ? frameObj.get("time").getAsInt() : -1;
                        frames.add(new FrameInfo(index, time));
                        maxFrameIndex = Math.max(maxFrameIndex, index);
                    }
                }
            }

            // Calculate frame count
            // If frames array exists, frame count is max index + 1
            // Otherwise, it will be inferred from texture height / width later
            int frameCount = maxFrameIndex >= 0 ? maxFrameIndex + 1 : 0;

            return new AnimationMetadata(frameTime, interpolate, width, height, frames, frameCount);

        } catch (IOException e) {
            LOGGER.debug("RecipeFlow: Failed to parse mcmeta: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Error parsing mcmeta: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Create a default animation metadata for textures without .mcmeta files.
     *
     * @param frameCount The number of frames (inferred from texture dimensions)
     * @return AnimationMetadata with default settings
     */
    public static AnimationMetadata createDefault(int frameCount) {
        return new AnimationMetadata(
                1,      // 1 tick per frame (50ms)
                false,  // No interpolation
                -1,     // Width inferred
                -1,     // Height inferred
                null,   // Sequential frames
                frameCount
        );
    }

    /**
     * Create animation metadata with custom frame time.
     *
     * @param frameCount The number of frames
     * @param frameTimeTicks Frame time in ticks
     * @return AnimationMetadata with specified settings
     */
    public static AnimationMetadata create(int frameCount, int frameTimeTicks) {
        return new AnimationMetadata(
                frameTimeTicks,
                false,
                -1,
                -1,
                null,
                frameCount
        );
    }
}

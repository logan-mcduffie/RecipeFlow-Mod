package com.recipeflow.mod.v120plus.client.screen;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Processes captured animation frames to create individual item animations.
 *
 * Processing strategy:
 * 1. Capture ALL frames at high FPS (don't early-terminate)
 * 2. Deduplicate consecutive identical frames (collapse runs)
 * 3. Find truly unique frames by comparing against all others
 * 4. Detect loop by finding when unique frame sequence repeats
 * 5. Validate against expected frame count from mcmeta if available
 */
@OnlyIn(Dist.CLIENT)
public class AnimationCaptureProcessor {

    private static final Logger LOGGER = LogManager.getLogger();

    // Frame comparison tolerance (0-255 per channel)
    // Lower = stricter matching (detects smaller differences)
    private static final int PIXEL_TOLERANCE = 3;
    // Maximum percentage of pixels that can differ and still match
    // Lower = stricter matching (fewer pixels can differ)
    private static final double MATCH_THRESHOLD = 0.005;  // 0.5% (was 2%)

    // Expected frame count from mcmeta (if known)
    private int expectedFrameCount = 0;

    /**
     * Set the expected frame count from mcmeta data.
     * This helps validate the detected animation frames.
     *
     * @param count Expected number of unique frames
     */
    public void setExpectedFrameCount(int count) {
        this.expectedFrameCount = count;
        LOGGER.debug("RecipeFlow AnimationProcessor: Expected frame count set to {}", count);
    }

    /**
     * Process a capture result and save individual item animations.
     *
     * @param result The capture result from AnimationCaptureScreen
     * @param outputDir Directory to save results
     * @return Number of animated items found
     */
    public int processAndSave(AnimationCaptureScreen.CaptureResult result, File outputDir) {
        outputDir.mkdirs();

        Map<ItemStack, List<BufferedImage>> allFrames = result.extractAllItemFrames();
        int animatedCount = 0;

        for (Map.Entry<ItemStack, List<BufferedImage>> entry : allFrames.entrySet()) {
            ItemStack stack = entry.getKey();
            List<BufferedImage> frames = entry.getValue();

            String itemName = stack.getItem().toString().replace(":", "_");

            ProcessedAnimation processed = processFrames(frames);

            if (processed.isAnimated) {
                animatedCount++;
                LOGGER.info("RecipeFlow AnimationProcessor: {} is animated ({} unique frames, loop at {})",
                        itemName, processed.uniqueFrames.size(), processed.loopPoint);

                // Save frames
                File itemDir = new File(outputDir, itemName);
                itemDir.mkdirs();

                for (int i = 0; i < processed.uniqueFrames.size(); i++) {
                    try {
                        File frameFile = new File(itemDir, String.format("frame_%03d.png", i));
                        ImageIO.write(processed.uniqueFrames.get(i), "PNG", frameFile);
                    } catch (Exception e) {
                        LOGGER.error("RecipeFlow AnimationProcessor: Failed to save frame: {}", e.getMessage());
                    }
                }

                LOGGER.info("RecipeFlow AnimationProcessor: Saved {} frames to {}",
                        processed.uniqueFrames.size(), itemDir.getAbsolutePath());
            } else {
                LOGGER.debug("RecipeFlow AnimationProcessor: {} is static (no animation detected)", itemName);

                // Save single frame for static items
                if (!frames.isEmpty()) {
                    try {
                        File frameFile = new File(outputDir, itemName + ".png");
                        ImageIO.write(frames.get(0), "PNG", frameFile);
                    } catch (Exception e) {
                        LOGGER.error("RecipeFlow AnimationProcessor: Failed to save static frame: {}", e.getMessage());
                    }
                }
            }
        }

        return animatedCount;
    }

    /**
     * Process frames to detect animation and find unique frames.
     *
     * Strategy:
     * 1. First, deduplicate consecutive identical frames (collapse runs of same frame)
     * 2. This gives us a list of "transition frames" - frames where something changed
     * 3. Then find truly unique frames by comparing all against each other
     * 4. Detect loop by finding when the unique sequence repeats
     * 5. Validate against expectedFrameCount if set
     */
    public ProcessedAnimation processFrames(List<BufferedImage> frames) {
        if (frames.isEmpty()) {
            return new ProcessedAnimation(false, frames, 0);
        }

        if (frames.size() < 2) {
            return new ProcessedAnimation(false, frames, 0);
        }

        LOGGER.info("RecipeFlow AnimationProcessor: Processing {} raw frames", frames.size());

        // Step 1: Deduplicate consecutive identical frames
        // This collapses runs like [A, A, A, B, B, C, C, C, A, A] -> [A, B, C, A]
        List<BufferedImage> transitionFrames = deduplicateConsecutive(frames);
        LOGGER.info("RecipeFlow AnimationProcessor: After consecutive dedup: {} frames", transitionFrames.size());

        if (transitionFrames.size() < 2) {
            // All frames were identical - not animated
            LOGGER.info("RecipeFlow AnimationProcessor: All frames identical, not animated");
            return new ProcessedAnimation(false, List.of(frames.get(0)), 0);
        }

        // Step 2: Find truly unique frames (comparing all against each other)
        List<BufferedImage> uniqueFrames = findUniqueFrames(transitionFrames);
        LOGGER.info("RecipeFlow AnimationProcessor: Found {} truly unique frames", uniqueFrames.size());

        // Step 3: Validate against expected frame count if set
        if (expectedFrameCount > 0 && uniqueFrames.size() != expectedFrameCount) {
            LOGGER.warn("RecipeFlow AnimationProcessor: Found {} unique frames but expected {} from mcmeta",
                    uniqueFrames.size(), expectedFrameCount);
            // If we have more frames than expected, we might have captured noise
            // If we have fewer, some frames might not have been captured
        }

        // Step 4: Detect loop point in the transition frames
        int loopPoint = findLoopPointInTransitions(transitionFrames, uniqueFrames);
        LOGGER.info("RecipeFlow AnimationProcessor: Loop detected at transition frame {}", loopPoint);

        boolean isAnimated = uniqueFrames.size() > 1;
        return new ProcessedAnimation(isAnimated, uniqueFrames, loopPoint);
    }

    /**
     * Remove consecutive identical frames, keeping only the first of each run.
     * [A, A, A, B, B, C, A, A] -> [A, B, C, A]
     */
    private List<BufferedImage> deduplicateConsecutive(List<BufferedImage> frames) {
        if (frames.size() < 2) return new ArrayList<>(frames);

        List<BufferedImage> result = new ArrayList<>();
        result.add(frames.get(0));

        for (int i = 1; i < frames.size(); i++) {
            if (!framesMatch(frames.get(i - 1), frames.get(i))) {
                result.add(frames.get(i));
            }
        }

        return result;
    }

    /**
     * Find truly unique frames by comparing each frame against all others.
     * This handles the case where the animation loops: [A, B, C, A, B, C] -> [A, B, C]
     */
    private List<BufferedImage> findUniqueFrames(List<BufferedImage> frames) {
        List<BufferedImage> unique = new ArrayList<>();

        for (BufferedImage frame : frames) {
            boolean isDuplicate = false;
            for (BufferedImage existing : unique) {
                if (framesMatch(frame, existing)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                unique.add(frame);
            }
        }

        return unique;
    }

    /**
     * Find where the animation loops by looking at the sequence of transition frames.
     * Returns the index in transitionFrames where we see the first unique frame again
     * after seeing all unique frames at least once.
     */
    private int findLoopPointInTransitions(List<BufferedImage> transitionFrames, List<BufferedImage> uniqueFrames) {
        if (uniqueFrames.size() < 2 || transitionFrames.size() < uniqueFrames.size()) {
            return 0;
        }

        // Map each transition frame to its unique frame index
        int[] frameIndices = new int[transitionFrames.size()];
        for (int i = 0; i < transitionFrames.size(); i++) {
            frameIndices[i] = findMatchingUniqueFrame(transitionFrames.get(i), uniqueFrames);
        }

        // Log the sequence for debugging
        StringBuilder sequence = new StringBuilder("Frame sequence: ");
        for (int idx : frameIndices) {
            sequence.append(idx).append(" ");
        }
        LOGGER.debug("RecipeFlow AnimationProcessor: {}", sequence);

        // Find where the sequence repeats
        // Look for the pattern: after seeing unique frames in some order, we see frame 0 again
        // This indicates the start of a new loop
        int uniqueCount = uniqueFrames.size();

        // Simple approach: find where we've seen all unique frames and then see the first one again
        boolean[] seen = new boolean[uniqueCount];
        int seenCount = 0;

        for (int i = 0; i < frameIndices.length; i++) {
            int idx = frameIndices[i];
            if (idx >= 0 && idx < uniqueCount) {
                if (!seen[idx]) {
                    seen[idx] = true;
                    seenCount++;
                }

                // After seeing all unique frames, if we see frame 0 again, that's the loop point
                if (seenCount == uniqueCount && idx == 0 && i > 0) {
                    return i;
                }
            }
        }

        return 0;  // No clear loop found
    }

    /**
     * Find which unique frame matches a given frame.
     */
    private int findMatchingUniqueFrame(BufferedImage frame, List<BufferedImage> uniqueFrames) {
        for (int i = 0; i < uniqueFrames.size(); i++) {
            if (framesMatch(frame, uniqueFrames.get(i))) {
                return i;
            }
        }
        return -1;  // No match found (shouldn't happen)
    }

    /**
     * Check if two frames are visually identical (within tolerance).
     */
    private boolean framesMatch(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }

        int totalPixels = a.getWidth() * a.getHeight();
        int diffPixels = 0;

        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (!pixelsMatch(a.getRGB(x, y), b.getRGB(x, y))) {
                    diffPixels++;
                }
            }
        }

        return diffPixels < (totalPixels * MATCH_THRESHOLD);
    }

    /**
     * Check if two pixels match within tolerance.
     */
    private boolean pixelsMatch(int a, int b) {
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int aB = a & 0xFF;

        int bA = (b >> 24) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        return Math.abs(aA - bA) <= PIXEL_TOLERANCE &&
               Math.abs(aR - bR) <= PIXEL_TOLERANCE &&
               Math.abs(aG - bG) <= PIXEL_TOLERANCE &&
               Math.abs(aB - bB) <= PIXEL_TOLERANCE;
    }

    /**
     * Result of processing animation frames.
     */
    public static class ProcessedAnimation {
        public final boolean isAnimated;
        public final List<BufferedImage> uniqueFrames;
        public final int loopPoint;

        public ProcessedAnimation(boolean isAnimated, List<BufferedImage> uniqueFrames, int loopPoint) {
            this.isAnimated = isAnimated;
            this.uniqueFrames = uniqueFrames;
            this.loopPoint = loopPoint;
        }
    }
}

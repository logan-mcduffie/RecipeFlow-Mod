package com.recipeflow.mod.core.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata for exported item icons.
 * Maps item IDs to their icon file information.
 */
public class IconMetadata {

    private final Map<String, IconEntry> icons;
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    public IconMetadata() {
        this.icons = new LinkedHashMap<>();
    }

    /**
     * Add an icon entry.
     *
     * @param itemId The item ID (e.g., "minecraft:iron_ingot")
     * @param entry The icon entry with file information
     */
    public void addIcon(String itemId, IconEntry entry) {
        icons.put(itemId, entry);
    }

    /**
     * Create and add a static icon entry.
     *
     * @param itemId The item ID
     * @param filename The icon filename (e.g., "minecraft/iron_ingot.png")
     */
    public void addStaticIcon(String itemId, String filename) {
        icons.put(itemId, IconEntry.staticIcon(filename));
    }

    /**
     * Create and add an animated icon entry.
     *
     * @param itemId The item ID
     * @param filename The icon filename (e.g., "minecraft/lava_bucket.webp")
     * @param frameCount Number of animation frames
     * @param frameTimeMs Milliseconds per frame
     */
    public void addAnimatedIcon(String itemId, String filename, int frameCount, int frameTimeMs) {
        icons.put(itemId, IconEntry.animatedIcon(filename, frameCount, frameTimeMs));
    }

    /**
     * Get all icon entries.
     */
    public Map<String, IconEntry> getIcons() {
        return icons;
    }

    /**
     * Get the number of icons.
     */
    public int size() {
        return icons.size();
    }

    /**
     * Serialize to JSON string.
     */
    public String toJson() {
        return GSON.toJson(icons);
    }

    /**
     * Deserialize from JSON string.
     */
    public static IconMetadata fromJson(String json) {
        Type mapType = new TypeToken<Map<String, IconEntry>>() {}.getType();
        Map<String, IconEntry> parsed = GSON.fromJson(json, mapType);

        IconMetadata metadata = new IconMetadata();
        if (parsed != null) {
            metadata.icons.putAll(parsed);
        }
        return metadata;
    }

    /**
     * Entry for a single icon file.
     */
    public static class IconEntry {
        private final String filename;
        private final boolean animated;
        private final int frameCount;
        private final int frameTimeMs;

        public IconEntry(String filename, boolean animated, int frameCount, int frameTimeMs) {
            this.filename = filename;
            this.animated = animated;
            this.frameCount = frameCount;
            this.frameTimeMs = frameTimeMs;
        }

        /**
         * Create entry for a static (non-animated) icon.
         */
        public static IconEntry staticIcon(String filename) {
            return new IconEntry(filename, false, 1, 0);
        }

        /**
         * Create entry for an animated icon.
         */
        public static IconEntry animatedIcon(String filename, int frameCount, int frameTimeMs) {
            return new IconEntry(filename, true, frameCount, frameTimeMs);
        }

        public String getFilename() {
            return filename;
        }

        public boolean isAnimated() {
            return animated;
        }

        public int getFrameCount() {
            return frameCount;
        }

        public int getFrameTimeMs() {
            return frameTimeMs;
        }
    }
}

package com.recipeflow.mod.v120plus.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Centralized helper for accessing obfuscated Minecraft fields and methods.
 *
 * Uses Forge's ObfuscationReflectionHelper which automatically handles the mapping
 * between dev environment names (MojMaps) and production SRG names.
 *
 * For SRG name lookups, use: https://linkie.shedaniel.dev/
 */
@OnlyIn(Dist.CLIENT)
public final class ObfuscationHelper {

    private static final Logger LOGGER = LogManager.getLogger();

    private ObfuscationHelper() {} // Utility class

    // ==================== Cached Fields ====================

    // SpriteContents.animatedTexture (SRG: f_118802_)
    private static Field spriteContentsAnimatedTextureField = null;
    private static boolean spriteContentsAnimatedTextureFieldInitialized = false;

    // SpriteContents.originalImage (the source NativeImage with all frames)
    private static Field spriteContentsOriginalImageField = null;
    private static boolean spriteContentsOriginalImageFieldInitialized = false;

    // ==================== SpriteContents ====================

    /**
     * Get the animatedTexture field from SpriteContents.
     * Returns null if not found or not accessible.
     */
    @Nullable
    public static Field getSpriteContentsAnimatedTextureField() {
        if (!spriteContentsAnimatedTextureFieldInitialized) {
            spriteContentsAnimatedTextureFieldInitialized = true;

            // Log all fields on SpriteContents for debugging
            LOGGER.info("RecipeFlow: SpriteContents fields: {}",
                    java.util.Arrays.toString(SpriteContents.class.getDeclaredFields()));

            // Try multiple SRG names - different MC versions use different names
            // f_244575_ confirmed for 1.20.1 via runtime inspection
            String[] possibleNames = {"f_244575_", "f_244704_", "f_118802_", "animatedTexture"};
            for (String name : possibleNames) {
                try {
                    spriteContentsAnimatedTextureField = ObfuscationReflectionHelper.findField(
                            SpriteContents.class, name);
                    LOGGER.info("RecipeFlow: Found SpriteContents.animatedTexture field via Forge helper: {}", name);
                    return spriteContentsAnimatedTextureField;
                } catch (Exception e) {
                    // Try direct field access as fallback
                    try {
                        spriteContentsAnimatedTextureField = SpriteContents.class.getDeclaredField(name);
                        spriteContentsAnimatedTextureField.setAccessible(true);
                        LOGGER.info("RecipeFlow: Found SpriteContents.animatedTexture field via direct access: {}", name);
                        return spriteContentsAnimatedTextureField;
                    } catch (NoSuchFieldException ignored) {}
                }
            }

            // Last resort: find by type (look for field of type containing "AnimatedTexture")
            for (Field field : SpriteContents.class.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.contains("AnimatedTexture")) {
                    field.setAccessible(true);
                    spriteContentsAnimatedTextureField = field;
                    LOGGER.info("RecipeFlow: Found SpriteContents.animatedTexture field by type: {} ({})",
                            field.getName(), typeName);
                    return spriteContentsAnimatedTextureField;
                }
            }

            LOGGER.error("RecipeFlow: Could not find animatedTexture field on SpriteContents");
        }
        return spriteContentsAnimatedTextureField;
    }

    /**
     * Get the AnimatedTexture object from a SpriteContents instance.
     */
    @Nullable
    public static Object getAnimatedTexture(SpriteContents contents) {
        Field field = getSpriteContentsAnimatedTextureField();
        if (field == null || contents == null) return null;
        try {
            return field.get(contents);
        } catch (IllegalAccessException e) {
            LOGGER.error("RecipeFlow: Could not access animatedTexture field: {}", e.getMessage());
            return null;
        }
    }

    // SpriteContents.byMipLevel (NativeImage[] used for rendering/uploading)
    private static Field spriteContentsByMipLevelField = null;
    private static boolean spriteContentsByMipLevelFieldInitialized = false;

    /**
     * Get the originalImage field from SpriteContents.
     * This contains the full source image with all animation frames as a vertical strip.
     * Prefers the single NativeImage field (f_243904_) over the array.
     */
    @Nullable
    public static Field getSpriteContentsOriginalImageField() {
        if (!spriteContentsOriginalImageFieldInitialized) {
            spriteContentsOriginalImageFieldInitialized = true;

            // First try to find the single NativeImage field (originalImage with all frames)
            // f_243904_ is the single NativeImage containing all animation frames vertically
            String[] singleImageNames = {"f_243904_", "originalImage"};
            for (String name : singleImageNames) {
                try {
                    spriteContentsOriginalImageField = ObfuscationReflectionHelper.findField(
                            SpriteContents.class, name);
                    LOGGER.info("RecipeFlow: Found SpriteContents.originalImage field via Forge helper: {}", name);
                    return spriteContentsOriginalImageField;
                } catch (Exception e) {
                    try {
                        spriteContentsOriginalImageField = SpriteContents.class.getDeclaredField(name);
                        spriteContentsOriginalImageField.setAccessible(true);
                        LOGGER.info("RecipeFlow: Found SpriteContents.originalImage field via direct access: {}", name);
                        return spriteContentsOriginalImageField;
                    } catch (NoSuchFieldException ignored) {}
                }
            }

            // Last resort: find by type (prefer single NativeImage over array)
            for (Field field : SpriteContents.class.getDeclaredFields()) {
                if (field.getType() == NativeImage.class) {
                    field.setAccessible(true);
                    spriteContentsOriginalImageField = field;
                    LOGGER.info("RecipeFlow: Found SpriteContents.originalImage field by type: {} (NativeImage)",
                            field.getName());
                    return spriteContentsOriginalImageField;
                }
            }

            LOGGER.error("RecipeFlow: Could not find originalImage field on SpriteContents");
        }
        return spriteContentsOriginalImageField;
    }

    /**
     * Get the byMipLevel field from SpriteContents.
     * This is the NativeImage[] array used for actual rendering and uploading.
     * Element 0 is full resolution, higher indices are mip levels.
     */
    @Nullable
    public static Field getSpriteContentsByMipLevelField() {
        if (!spriteContentsByMipLevelFieldInitialized) {
            spriteContentsByMipLevelFieldInitialized = true;

            // f_243731_ is the NativeImage[] byMipLevel array
            String[] arrayNames = {"f_243731_", "byMipLevel"};
            for (String name : arrayNames) {
                try {
                    spriteContentsByMipLevelField = ObfuscationReflectionHelper.findField(
                            SpriteContents.class, name);
                    LOGGER.info("RecipeFlow: Found SpriteContents.byMipLevel field via Forge helper: {}", name);
                    return spriteContentsByMipLevelField;
                } catch (Exception e) {
                    try {
                        spriteContentsByMipLevelField = SpriteContents.class.getDeclaredField(name);
                        spriteContentsByMipLevelField.setAccessible(true);
                        LOGGER.info("RecipeFlow: Found SpriteContents.byMipLevel field via direct access: {}", name);
                        return spriteContentsByMipLevelField;
                    } catch (NoSuchFieldException ignored) {}
                }
            }

            // Last resort: find NativeImage[] by type
            for (Field field : SpriteContents.class.getDeclaredFields()) {
                if (field.getType() == NativeImage[].class) {
                    field.setAccessible(true);
                    spriteContentsByMipLevelField = field;
                    LOGGER.info("RecipeFlow: Found SpriteContents.byMipLevel field by type: {} (NativeImage[])",
                            field.getName());
                    return spriteContentsByMipLevelField;
                }
            }

            LOGGER.error("RecipeFlow: Could not find byMipLevel field on SpriteContents");
        }
        return spriteContentsByMipLevelField;
    }

    /**
     * Get the original NativeImage from a SpriteContents instance.
     * This image contains all animation frames stacked vertically.
     *
     * @param contents The SpriteContents to extract from
     * @return The NativeImage, or null if not accessible
     */
    @Nullable
    public static NativeImage getOriginalImage(SpriteContents contents) {
        Field field = getSpriteContentsOriginalImageField();
        if (field == null || contents == null) return null;
        try {
            Object value = field.get(contents);
            if (value instanceof NativeImage) {
                return (NativeImage) value;
            }
            LOGGER.warn("RecipeFlow: originalImage field has unexpected type: {}",
                    value != null ? value.getClass().getName() : "null");
            return null;
        } catch (IllegalAccessException e) {
            LOGGER.error("RecipeFlow: Could not access originalImage field: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the byMipLevel NativeImage array from a SpriteContents instance.
     * This is used for uploading frames to the texture atlas.
     *
     * @param contents The SpriteContents to extract from
     * @return The NativeImage[] array, or null if not accessible
     */
    @Nullable
    public static NativeImage[] getByMipLevel(SpriteContents contents) {
        Field field = getSpriteContentsByMipLevelField();
        if (field == null || contents == null) return null;
        try {
            Object value = field.get(contents);
            if (value instanceof NativeImage[]) {
                return (NativeImage[]) value;
            }
            LOGGER.warn("RecipeFlow: byMipLevel field has unexpected type: {}",
                    value != null ? value.getClass().getName() : "null");
            return null;
        } catch (IllegalAccessException e) {
            LOGGER.error("RecipeFlow: Could not access byMipLevel field: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Generic Reflection Helpers ====================

    /**
     * Find a field by trying multiple possible names.
     * Uses Forge's helper first (handles SRG mapping), then falls back to direct lookup.
     *
     * @param clazz The class to search in
     * @param possibleNames Array of possible field names (SRG name first, then alternatives)
     * @return The Field if found, null otherwise
     */
    @Nullable
    public static Field findField(Class<?> clazz, String... possibleNames) {
        for (String name : possibleNames) {
            try {
                // Try Forge's helper first (handles obfuscation mapping)
                Field field = ObfuscationReflectionHelper.findField(clazz, name);
                return field;
            } catch (Exception e1) {
                // Fall back to direct lookup
                try {
                    Field field = clazz.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {}
            }
        }
        return null;
    }

    /**
     * Find a method by trying multiple possible names (no parameters).
     *
     * @param clazz The class to search in
     * @param possibleNames Array of possible method names
     * @return The Method if found, null otherwise
     */
    @Nullable
    public static Method findMethod(Class<?> clazz, String... possibleNames) {
        for (String name : possibleNames) {
            try {
                Method method = ObfuscationReflectionHelper.findMethod(clazz, name);
                return method;
            } catch (Exception e1) {
                try {
                    Method method = clazz.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    /**
     * Find a method by trying multiple possible names with specific parameter types.
     *
     * @param clazz The class to search in
     * @param paramTypes The parameter types
     * @param possibleNames Array of possible method names
     * @return The Method if found, null otherwise
     */
    @Nullable
    public static Method findMethod(Class<?> clazz, Class<?>[] paramTypes, String... possibleNames) {
        for (String name : possibleNames) {
            try {
                Method method = ObfuscationReflectionHelper.findMethod(clazz, name, paramTypes);
                return method;
            } catch (Exception e1) {
                try {
                    Method method = clazz.getDeclaredMethod(name, paramTypes);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    /**
     * Get a field value, returning null if the field doesn't exist or access fails.
     *
     * @param obj The object to get the field from
     * @param possibleNames Possible field names to try
     * @return The field value, or null if not found/accessible
     */
    @Nullable
    public static Object getFieldValue(Object obj, String... possibleNames) {
        Field field = findField(obj.getClass(), possibleNames);
        if (field == null) return null;
        try {
            return field.get(obj);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Get a field value with a specific expected type.
     *
     * @param obj The object to get the field from
     * @param expectedType The expected type of the field value
     * @param possibleNames Possible field names to try
     * @return The field value cast to T, or null if not found/wrong type
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> T getFieldValue(Object obj, Class<T> expectedType, String... possibleNames) {
        Object value = getFieldValue(obj, possibleNames);
        if (value != null && expectedType.isInstance(value)) {
            return (T) value;
        }
        return null;
    }

    // ==================== Known SRG Names (for reference) ====================
    // These are documented here for reference when looking up names
    // Use https://linkie.shedaniel.dev/ to find SRG names for specific MC versions

    /**
     * SpriteContents.animatedTexture - The AnimatedTexture inner class instance
     * Type: SpriteContents.AnimatedTexture (nullable)
     * SRG: f_244575_ (1.20.1) - confirmed via runtime inspection
     */
    public static final String[] SPRITE_CONTENTS_ANIMATED_TEXTURE = {
        "f_244575_", "f_244704_", "f_118802_", "animatedTexture"
    };

    /**
     * AnimatedTexture.frames - List of FrameInfo objects
     * Type: List<SpriteContents.FrameInfo>
     * SRG: f_243714_
     */
    public static final String[] ANIMATED_TEXTURE_FRAMES = {
        "f_243714_", "frames"
    };

    /**
     * AnimatedTexture.uploadFrame - Method to upload a specific frame
     * Signature: void uploadFrame(int x, int y, int frameIndex)
     * SRG: m_245074_
     */
    public static final String[] ANIMATED_TEXTURE_UPLOAD_FRAME = {
        "m_245074_", "uploadFrame", "invokeUploadFrame"
    };

    /**
     * FrameInfo.index - The frame index in the sprite sheet
     * Type: int
     * SRG: f_243697_
     */
    public static final String[] FRAME_INFO_INDEX = {
        "f_243697_", "index"
    };

    /**
     * FrameInfo.time - Duration of this frame in ticks
     * Type: int
     * SRG: f_243698_
     */
    public static final String[] FRAME_INFO_TIME = {
        "f_243698_", "time"
    };

    /**
     * Ticker on SpriteContents$Ticker - Reference to AnimatedTexture
     * Type: SpriteContents.AnimatedTexture
     * SRG: f_243921_
     */
    public static final String[] TICKER_ANIMATION_INFO = {
        "f_243921_", "f_244575_", "animationInfo"
    };

    /**
     * TextureAtlasSprite$1 wrapper - val$spriteticker field
     * Type: SpriteTicker
     */
    public static final String[] TICKER_WRAPPER_INNER_TICKER = {
        "val$spriteticker", "spriteticker"
    };

    /**
     * TextureAtlasSprite$1 wrapper - sprite reference
     * Type: TextureAtlasSprite
     * SRG: f_243782_
     */
    public static final String[] TICKER_WRAPPER_SPRITE = {
        "f_243782_", "val$sprite", "sprite"
    };

    /**
     * Wrapper ticker tickAndUpload method (no params)
     * SRG: m_245385_
     */
    public static final String[] TICKER_TICK_AND_UPLOAD = {
        "m_245385_", "tickAndUpload"
    };

    /**
     * AnimatedTexture.interpolateFrames - Whether to interpolate between frames
     * Type: boolean
     * SRG: f_244317_
     */
    public static final String[] ANIMATED_TEXTURE_INTERPOLATE = {
        "f_244317_", "interpolateFrames"
    };

    /**
     * SpriteContents.byMipLevel - Array of NativeImages for each mip level
     * Type: NativeImage[]
     * The first element (index 0) is the full-resolution source image with all frames
     * SRG: f_244576_
     */
    public static final String[] SPRITE_CONTENTS_BY_MIP_LEVEL = {
        "f_244576_", "f_118803_", "byMipLevel", "originalImage"
    };
}

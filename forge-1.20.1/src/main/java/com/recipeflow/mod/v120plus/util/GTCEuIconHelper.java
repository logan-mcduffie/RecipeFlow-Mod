package com.recipeflow.mod.v120plus.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Helper class for extracting overlay sprites from GTCEu machines.
 * Uses the MachineDefinition API to get correctly-colored overlays.
 *
 * GTCEu uses separate pre-colored texture files for input (green) and output (red)
 * overlays rather than runtime vertex tinting. This helper determines which
 * overlay textures to use based on the machine type.
 *
 * This class isolates GTCEu class loading to ensure graceful fallback
 * when GTCEu is not present.
 */
@OnlyIn(Dist.CLIENT)
public class GTCEuIconHelper {

    private static final Logger LOGGER = LogManager.getLogger();

    // GTCEu tier constants (OpV = 13, MAX = 14)
    public static final int TIER_OPV = 13;
    public static final int TIER_MAX = 14;

    // Color cycle speed for OpV tier (degrees of hue change per tick)
    // From TooltipHelper.RAINBOW_HSL_SLOW = 1.25f
    private static final float OPV_COLOR_CYCLE_SPEED = 1.25f;

    // Number of ticks for a full 360° color cycle at RAINBOW_HSL_SLOW speed
    // 360 / 1.25 = 288 ticks = 14.4 seconds
    private static final int OPV_FULL_CYCLE_TICKS = (int) (360f / OPV_COLOR_CYCLE_SPEED);

    // GTCEu overlay texture base path
    private static final String OVERLAY_BASE = "gtceu:block/overlay/machine/";

    // Cache for overlay sprites to avoid redundant expensive reflection calls
    // Key is the item registry name, value is the cached sprite list
    private static final Map<String, List<TextureAtlasSprite>> spriteCache = new HashMap<>();

    /**
     * Clear the sprite cache. Call this if textures might have changed (e.g., resource reload).
     */
    public static void clearCache() {
        spriteCache.clear();
    }

    /**
     * Get overlay sprites for a GTCEu machine item.
     * Returns correctly-colored overlays based on MachineDefinition.
     * Results are cached to avoid expensive repeated reflection calls.
     *
     * @param stack The item stack to check
     * @return List of animated overlay sprites, empty if not a GTCEu machine or no overlays found
     */
    public static List<TextureAtlasSprite> getOverlaySprites(ItemStack stack) {
        // Check cache first
        String cacheKey = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
        if (spriteCache.containsKey(cacheKey)) {
            return spriteCache.get(cacheKey);
        }

        try {
            List<TextureAtlasSprite> result = getOverlaySpritesInternal(stack);
            spriteCache.put(cacheKey, result);
            return result;
        } catch (NoClassDefFoundError e) {
            // GTCEu classes not available
            LOGGER.debug("RecipeFlow: GTCEu classes not available: {}", e.getMessage());
            spriteCache.put(cacheKey, Collections.emptyList());
            return Collections.emptyList();
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow: Error getting GTCEu overlays: {}", e.getMessage());
            spriteCache.put(cacheKey, Collections.emptyList());
            return Collections.emptyList();
        }
    }

    /**
     * Check if an ItemStack is a GTCEu machine.
     *
     * @param stack The item stack to check
     * @return true if this is a GTCEu MetaMachineItem
     */
    public static boolean isGTCEuMachine(ItemStack stack) {
        try {
            return isGTCEuMachineInternal(stack);
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Internal method to check if stack is GTCEu machine.
     * Isolated to prevent class loading when GTCEu is absent.
     */
    private static boolean isGTCEuMachineInternal(ItemStack stack) {
        return stack.getItem() instanceof com.gregtechceu.gtceu.api.item.MetaMachineItem;
    }

    /**
     * Get the tier of a GTCEu machine.
     *
     * @param stack The item stack to check
     * @return The machine tier (0-14), or -1 if not a tiered machine
     */
    public static int getMachineTier(ItemStack stack) {
        try {
            return getMachineTierInternal(stack);
        } catch (NoClassDefFoundError e) {
            return -1;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error getting machine tier: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Internal method to get machine tier.
     */
    private static int getMachineTierInternal(ItemStack stack) {
        if (!(stack.getItem() instanceof com.gregtechceu.gtceu.api.item.MetaMachineItem machineItem)) {
            return -1;
        }

        com.gregtechceu.gtceu.api.machine.MachineDefinition definition = machineItem.getDefinition();
        if (definition == null) {
            return -1;
        }

        return definition.getTier();
    }

    /**
     * Check if a machine uses time-based tint color cycling (like OPV tier).
     *
     * @param stack The item stack to check
     * @return true if this machine has animated tint colors
     */
    public static boolean hasAnimatedTint(ItemStack stack) {
        int tier = getMachineTier(stack);
        // OpV (tier 13) and MAX (tier 14) use rainbow color cycling
        return tier == TIER_OPV || tier == TIER_MAX;
    }

    /**
     * Get the recommended frame count for capturing a machine's full animation,
     * including both sprite animation and tint color cycling.
     *
     * @param stack The item stack to check
     * @param baseSpriteFrames The number of sprite animation frames
     * @return The recommended total frame count for GIF capture
     */
    public static int getRecommendedFrameCount(ItemStack stack, int baseSpriteFrames) {
        if (!hasAnimatedTint(stack)) {
            return baseSpriteFrames;
        }

        // For OPV/MAX tier, we need enough frames to show the color cycle
        // A full cycle is 288 ticks at 1.25 deg/tick
        // For a reasonable GIF size, capture ~72 frames (every 4 ticks = 5 FPS equivalent)
        // This gives a ~3.6 second loop through the full rainbow
        int colorCycleFrames = 72;

        // Return the LCM of sprite frames and color cycle frames for smooth looping
        // But cap it to prevent enormous GIFs
        int combined = lcm(baseSpriteFrames, colorCycleFrames);
        return Math.min(combined, 144); // Cap at 144 frames (~7 seconds at 20 FPS)
    }

    /**
     * Get the tick interval between frames for OPV color cycling.
     * This determines how much to advance CLIENT_TIME between captures.
     *
     * @return Ticks to advance between frames (4 ticks = capture every 200ms for smooth color)
     */
    public static int getColorCycleTickInterval() {
        return 4; // Every 4 ticks = 5° of hue change per frame
    }

    /**
     * Get the frame duration in milliseconds for OPV color cycling GIFs.
     *
     * @return Frame duration in ms (200ms = 5 FPS for color cycling)
     */
    public static int getColorCycleFrameDurationMs() {
        return getColorCycleTickInterval() * 50; // 50ms per tick
    }

    /**
     * Calculate least common multiple.
     */
    private static int lcm(int a, int b) {
        return (a * b) / gcd(a, b);
    }

    /**
     * Calculate greatest common divisor.
     */
    private static int gcd(int a, int b) {
        while (b != 0) {
            int temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    // Cached CLIENT_TIME field for reflection access
    private static java.lang.reflect.Field clientTimeField = null;
    private static boolean clientTimeFieldSearched = false;

    /**
     * Get the current GTValues.CLIENT_TIME value.
     *
     * @return The current client time, or -1 if unavailable
     */
    public static long getClientTime() {
        try {
            return getClientTimeInternal();
        } catch (NoClassDefFoundError e) {
            return -1;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error getting CLIENT_TIME: {}", e.getMessage());
            return -1;
        }
    }

    private static long getClientTimeInternal() throws Exception {
        if (!clientTimeFieldSearched) {
            clientTimeFieldSearched = true;
            try {
                Class<?> gtValuesClass = Class.forName("com.gregtechceu.gtceu.api.GTValues");
                clientTimeField = gtValuesClass.getDeclaredField("CLIENT_TIME");
                clientTimeField.setAccessible(true);
            } catch (Exception e) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Could not find CLIENT_TIME field: {}", e.getMessage());
            }
        }

        if (clientTimeField == null) {
            return -1;
        }

        return clientTimeField.getLong(null);
    }

    /**
     * Set the GTValues.CLIENT_TIME value for animation capture.
     * IMPORTANT: This should only be used during capture and restored afterward!
     *
     * @param time The time value to set
     * @return true if successful
     */
    public static boolean setClientTime(long time) {
        try {
            return setClientTimeInternal(time);
        } catch (NoClassDefFoundError e) {
            return false;
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error setting CLIENT_TIME: {}", e.getMessage());
            return false;
        }
    }

    private static boolean setClientTimeInternal(long time) throws Exception {
        if (!clientTimeFieldSearched) {
            getClientTimeInternal(); // Initialize the field
        }

        if (clientTimeField == null) {
            return false;
        }

        clientTimeField.setLong(null, time);
        return true;
    }

    /**
     * Advance CLIENT_TIME by the specified number of ticks.
     * Used for simulating time progression during animation capture.
     *
     * @param ticks Number of ticks to advance
     * @return The new CLIENT_TIME value, or -1 on failure
     */
    public static long advanceClientTime(int ticks) {
        long current = getClientTime();
        if (current < 0) return -1;

        long newTime = current + ticks;
        if (setClientTime(newTime)) {
            return newTime;
        }
        return -1;
    }

    /**
     * Internal method using GTCEu classes directly.
     * Isolated to prevent class loading when GTCEu is absent.
     */
    private static List<TextureAtlasSprite> getOverlaySpritesInternal(ItemStack stack) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();

        // Check if this is a GTCEu machine item
        LOGGER.debug("RecipeFlow GTCEuIconHelper: Checking item class: {}", stack.getItem().getClass().getName());

        if (!(stack.getItem() instanceof com.gregtechceu.gtceu.api.item.MetaMachineItem machineItem)) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Item is NOT a MetaMachineItem");
            return sprites;
        }

        LOGGER.debug("RecipeFlow GTCEuIconHelper: Item IS a MetaMachineItem");

        // Get the MachineDefinition
        com.gregtechceu.gtceu.api.machine.MachineDefinition definition = machineItem.getDefinition();

        if (definition == null) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: MachineDefinition is null for {}", stack.getItem());
            return sprites;
        }

        // Get machine ID for pattern matching
        String machineId = definition.getId().getPath();
        LOGGER.debug("RecipeFlow GTCEuIconHelper: Processing GTCEu machine: {}", machineId);

        // First try to get overlays from the MachineDefinition's appearance/renderer
        List<TextureAtlasSprite> apiSprites = getOverlaysFromDefinition(definition, machineId);
        if (!apiSprites.isEmpty()) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Found {} sprites from MachineDefinition API for {}",
                apiSprites.size(), machineId);
            return apiSprites;
        }

        // Fall back to pattern matching
        List<String> overlayPaths = determineOverlayPaths(machineId);
        LOGGER.debug("RecipeFlow GTCEuIconHelper: Checking {} overlay paths: {}", overlayPaths.size(), overlayPaths);

        // Load sprites from texture atlas
        Function<ResourceLocation, TextureAtlasSprite> textureAtlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

        for (String overlayPath : overlayPaths) {
            ResourceLocation location = ResourceLocation.tryParse(overlayPath);
            if (location != null) {
                TextureAtlasSprite sprite = textureAtlas.apply(location);

                if (sprite == null) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Sprite is null for {}", overlayPath);
                    continue;
                }

                String spriteName = sprite.contents().name().toString();
                long frameCount = sprite.contents().getUniqueFrames().count();
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Sprite {} -> {} ({} frames)",
                    overlayPath, spriteName, frameCount);

                // Check if sprite is valid and not missing texture
                if (!sprite.contents().name().getPath().equals("missingno")) {
                    if (frameCount > 1) {
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: Adding animated overlay {} ({} frames) for {}",
                            overlayPath, frameCount, machineId);
                        sprites.add(sprite);
                    } else {
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: Sprite {} has only {} frame(s), skipping",
                            overlayPath, frameCount);
                    }
                } else {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Sprite {} is missingno, skipping", overlayPath);
                }
            }
        }

        LOGGER.debug("RecipeFlow GTCEuIconHelper: Returning {} sprites for {}", sprites.size(), machineId);
        return sprites;
    }

    /**
     * Try to get overlay textures directly from the MachineDefinition's renderer/appearance.
     * This accesses GTCEu's internal API to find what textures are actually used.
     */
    private static List<TextureAtlasSprite> getOverlaysFromDefinition(
            com.gregtechceu.gtceu.api.machine.MachineDefinition definition, String machineId) {
        List<TextureAtlasSprite> sprites = new ArrayList<>();

        try {
            Function<ResourceLocation, TextureAtlasSprite> textureAtlas =
                Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

            // Log available methods for debugging
            LOGGER.debug("RecipeFlow GTCEuIconHelper: MachineDefinition class: {}", definition.getClass().getName());

            // Try reflection to find overlay/appearance methods
            for (Method method : definition.getClass().getMethods()) {
                String methodName = method.getName().toLowerCase();
                if (methodName.contains("overlay") || methodName.contains("texture") ||
                    methodName.contains("appearance") || methodName.contains("renderer") ||
                    methodName.contains("model") || methodName.contains("block")) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Found method: {} -> {}",
                        method.getName(), method.getReturnType().getSimpleName());
                }
            }

            // Try to get sprites from the IRenderer
            List<TextureAtlasSprite> rendererSprites = getSpritesFromRenderer(definition, machineId);
            if (!rendererSprites.isEmpty()) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Found {} animated sprites from IRenderer for {}",
                    rendererSprites.size(), machineId);
                return rendererSprites;
            }

            // Try to call getAppearance() if it exists
            try {
                var appearanceMethod = definition.getClass().getMethod("getAppearance");
                Object appearance = appearanceMethod.invoke(definition);
                if (appearance != null) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Got appearance object: {}", appearance.getClass().getName());

                    // Log all methods on the appearance object
                    for (Method method : appearance.getClass().getMethods()) {
                        String methodName = method.getName().toLowerCase();
                        if (methodName.contains("texture") || methodName.contains("overlay") ||
                            methodName.contains("sprite") || methodName.contains("front") ||
                            methodName.contains("active") || methodName.contains("emissive")) {
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Appearance method: {} -> {}",
                                method.getName(), method.getReturnType().getSimpleName());
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: No getAppearance method found");
            }

            // Try specific texture path patterns based on machine ID
            String[] possiblePaths = {
                "gtceu:block/multiblock/" + machineId,
                "gtceu:block/multiblock/" + machineId + "_emissive",
                "gtceu:block/machines/" + machineId,
                "gtceu:block/machines/" + machineId + "/overlay_front",
                "gtceu:block/machines/" + machineId + "/overlay_front_emissive",
                // Fusion reactor specific
                "gtceu:block/multiblock/fusion_reactor",
                "gtceu:block/multiblock/fusion_reactor_emissive",
                "gtceu:block/overlay/machine/overlay_fusion",
                "gtceu:block/overlay/machine/overlay_fusion_emissive",
                // Try the controller overlays
                "gtceu:block/overlay/machine/overlay_front_fusion_reactor",
                "gtceu:block/overlay/machine/overlay_front_fusion_reactor_active",
                "gtceu:block/overlay/machine/overlay_front_fusion_reactor_active_emissive",
                // Common multiblock patterns
                "gtceu:block/overlay/multiblock/" + machineId,
                "gtceu:block/overlay/multiblock/" + machineId + "_active",
                "gtceu:block/overlay/multiblock/" + machineId + "_active_emissive",
            };

            for (String path : possiblePaths) {
                ResourceLocation location = ResourceLocation.tryParse(path);
                if (location != null) {
                    TextureAtlasSprite sprite = textureAtlas.apply(location);
                    if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                        long frameCount = sprite.contents().getUniqueFrames().count();
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: Checked {} -> {} ({} frames)",
                            path, sprite.contents().name(), frameCount);
                        if (frameCount > 1) {
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Found animated texture {} ({} frames) for {}",
                                path, frameCount, machineId);
                            sprites.add(sprite);
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error accessing MachineDefinition API: {}", e.getMessage());
        }

        return sprites;
    }

    /**
     * Get animated sprites from the IRenderer's renderModel() method.
     * The IRenderer interface provides renderModel() which returns List<BakedQuad>,
     * and each quad has a sprite we can extract.
     */
    private static List<TextureAtlasSprite> getSpritesFromRenderer(
            com.gregtechceu.gtceu.api.machine.MachineDefinition definition, String machineId) {
        Set<TextureAtlasSprite> animatedSprites = new HashSet<>();

        try {
            // Get the IRenderer from the MachineDefinition
            Method getRendererMethod = definition.getClass().getMethod("getRenderer");
            Object renderer = getRendererMethod.invoke(definition);

            if (renderer == null) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: getRenderer() returned null for {}", machineId);
                return new ArrayList<>();
            }

            LOGGER.debug("RecipeFlow GTCEuIconHelper: Got IRenderer: {} for {}",
                renderer.getClass().getName(), machineId);

            // Log all methods on the renderer for debugging
            for (Method method : renderer.getClass().getMethods()) {
                String methodName = method.getName().toLowerCase();
                if (methodName.contains("model") || methodName.contains("quad") ||
                    methodName.contains("texture") || methodName.contains("sprite") ||
                    methodName.contains("render") || methodName.contains("overlay")) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: IRenderer method: {} -> {} (params: {})",
                        method.getName(), method.getReturnType().getSimpleName(), method.getParameterCount());
                }
            }

            // Try to call renderModel() - it has various overloads
            // The simplest one might be: List<BakedQuad> renderModel(BlockAndTintGetter, BlockPos, BlockState, Direction, RandomSource)
            // Or a simpler version without parameters

            // First, try the no-arg version if it exists
            List<?> quads = null;

            // Try different method signatures for renderModel
            try {
                // Try renderModel with null parameters (common pattern)
                Method renderModelMethod = findRenderModelMethod(renderer.getClass());
                if (renderModelMethod != null) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Found renderModel method: {} with {} params",
                        renderModelMethod.getName(), renderModelMethod.getParameterCount());

                    // Call with appropriate null/default parameters
                    Object result = invokeRenderModel(renderModelMethod, renderer);
                    if (result instanceof List) {
                        quads = (List<?>) result;
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: renderModel returned {} quads", quads.size());
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Error calling renderModel: {}", e.getMessage());
            }

            // Extract sprites from quads
            if (quads != null) {
                for (Object quad : quads) {
                    if (quad instanceof BakedQuad bakedQuad) {
                        TextureAtlasSprite sprite = bakedQuad.getSprite();
                        if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                            long frameCount = sprite.contents().getUniqueFrames().count();
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Quad sprite: {} ({} frames)",
                                sprite.contents().name(), frameCount);
                            if (frameCount > 1) {
                                animatedSprites.add(sprite);
                            }
                        }
                    }
                }
            }

            // Also try to get the particle texture from IRenderer
            try {
                Method getParticleMethod = renderer.getClass().getMethod("getParticleTexture");
                Object particleSprite = getParticleMethod.invoke(renderer);
                if (particleSprite instanceof TextureAtlasSprite sprite) {
                    long frameCount = sprite.contents().getUniqueFrames().count();
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Particle sprite: {} ({} frames)",
                        sprite.contents().name(), frameCount);
                    if (frameCount > 1) {
                        animatedSprites.add(sprite);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Could not get particle texture: {}", e.getMessage());
            }

            // Try to find overlay textures registered via onPrepareTextureAtlas
            // by checking if the renderer has any texture location fields
            extractTextureFieldsFromRenderer(renderer, animatedSprites, machineId);

        } catch (NoSuchMethodException e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: No getRenderer method found for {}", machineId);
        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error getting sprites from renderer for {}: {}",
                machineId, e.getMessage());
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Stack trace:", e);
            }
        }

        return new ArrayList<>(animatedSprites);
    }

    /**
     * Find the renderModel method on an IRenderer implementation.
     */
    private static Method findRenderModelMethod(Class<?> rendererClass) {
        // Try various signatures
        for (Method method : rendererClass.getMethods()) {
            if (method.getName().equals("renderModel")) {
                return method;
            }
        }
        return null;
    }

    /**
     * Invoke renderModel with appropriate parameters based on its signature.
     */
    private static Object invokeRenderModel(Method method, Object renderer) throws Exception {
        int paramCount = method.getParameterCount();

        if (paramCount == 0) {
            return method.invoke(renderer);
        } else if (paramCount == 5) {
            // renderModel(BlockAndTintGetter, BlockPos, BlockState, Direction, RandomSource)
            return method.invoke(renderer, null, null, null, null,
                net.minecraft.util.RandomSource.create());
        } else {
            // Try with all nulls
            Object[] params = new Object[paramCount];
            // Fill in RandomSource if needed (last param is often RandomSource)
            Class<?>[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramCount; i++) {
                if (paramTypes[i].getSimpleName().contains("RandomSource") ||
                    paramTypes[i].getSimpleName().contains("Random")) {
                    params[i] = net.minecraft.util.RandomSource.create();
                }
            }
            return method.invoke(renderer, params);
        }
    }

    /**
     * Extract texture ResourceLocations from renderer fields and convert to sprites.
     */
    private static void extractTextureFieldsFromRenderer(Object renderer,
            Set<TextureAtlasSprite> animatedSprites, String machineId) {
        Function<ResourceLocation, TextureAtlasSprite> textureAtlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

        try {
            // Log ALL fields from the renderer class for debugging
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Scanning {} fields from {}",
                renderer.getClass().getDeclaredFields().length, renderer.getClass().getSimpleName());

            // Look for ResourceLocation fields that might be textures
            for (java.lang.reflect.Field field : renderer.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(renderer);

                // Log all fields for debugging
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Renderer field '{}' ({}): {}",
                    field.getName(), field.getType().getSimpleName(),
                    value != null ? value.toString().substring(0, Math.min(100, value.toString().length())) : "null");

                if (value instanceof ResourceLocation location) {
                    TextureAtlasSprite sprite = textureAtlas.apply(location);
                    if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                        long frameCount = sprite.contents().getUniqueFrames().count();
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: Field '{}' sprite: {} ({} frames)",
                            field.getName(), location, frameCount);
                        if (frameCount > 1) {
                            animatedSprites.add(sprite);
                        }
                    }
                } else if (value instanceof ResourceLocation[] locations) {
                    for (int i = 0; i < locations.length; i++) {
                        ResourceLocation location = locations[i];
                        if (location != null) {
                            TextureAtlasSprite sprite = textureAtlas.apply(location);
                            if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                                long frameCount = sprite.contents().getUniqueFrames().count();
                                LOGGER.debug("RecipeFlow GTCEuIconHelper: Array field '{}[{}]' sprite: {} ({} frames)",
                                    field.getName(), i, location, frameCount);
                                if (frameCount > 1) {
                                    animatedSprites.add(sprite);
                                }
                            }
                        }
                    }
                }
            }

            // Try to get the model location - this may lead us to textures
            try {
                Method getModelLocation = renderer.getClass().getMethod("getModelLocation");
                Object modelLoc = getModelLocation.invoke(renderer);
                if (modelLoc instanceof ResourceLocation location) {
                    LOGGER.debug("RecipeFlow GTCEuIconHelper: getModelLocation() returned: {}", location);
                    // The model location points to a JSON model file, not a texture directly
                    // But we can try variations to find related textures
                    String basePath = location.getPath();
                    tryTextureVariations(basePath, location.getNamespace(), textureAtlas, animatedSprites, machineId);
                }
            } catch (Exception e) {
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Could not call getModelLocation: {}", e.getMessage());
            }

            // For multiblock controllers like fusion reactor, try specific overlay paths
            // GTCEu uses overlay_screen for many controllers with animated screens
            if (machineId.contains("fusion") || machineId.contains("reactor")) {
                // Try fusion-specific texture paths
                String[] fusionPaths = {
                    "gtceu:block/overlay/machine/overlay_screen",
                    "gtceu:block/overlay/machine/overlay_screen_emissive",
                    "gtceu:block/overlay/machine/overlay_fusion_screen",
                    "gtceu:block/overlay/machine/overlay_fusion_screen_emissive",
                    "gtceu:block/multiblock/fusion/overlay_front",
                    "gtceu:block/multiblock/fusion/overlay_front_active",
                    "gtceu:block/multiblock/fusion/overlay_front_active_emissive",
                };

                for (String path : fusionPaths) {
                    ResourceLocation loc = ResourceLocation.tryParse(path);
                    if (loc != null) {
                        TextureAtlasSprite sprite = textureAtlas.apply(loc);
                        if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                            long frameCount = sprite.contents().getUniqueFrames().count();
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Fusion path '{}': {} ({} frames)",
                                path, sprite.contents().name(), frameCount);
                            if (frameCount > 1) {
                                animatedSprites.add(sprite);
                            }
                        }
                    }
                }
            }

            // Check superclass fields - but be more selective to avoid generic overlays
            // that aren't actually used by this specific machine
            Class<?> superClass = renderer.getClass().getSuperclass();

            while (superClass != null && !superClass.equals(Object.class)) {
                for (java.lang.reflect.Field field : superClass.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(renderer);

                    if (value instanceof ResourceLocation location) {
                        String fieldName = field.getName().toLowerCase();
                        String locationPath = location.getPath().toLowerCase();

                        // Skip generic output/input overlays unless this is actually an output/input machine
                        boolean isOutputOverlay = locationPath.contains("output");
                        boolean isInputOverlay = locationPath.contains("input");
                        boolean machineIsOutput = machineId.contains("output") || machineId.contains("export");
                        boolean machineIsInput = machineId.contains("input") || machineId.contains("import");

                        // Skip mismatched overlays
                        if (isOutputOverlay && !machineIsOutput && !machineId.contains("dual")) {
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Skipping output overlay {} for non-output machine {}",
                                location, machineId);
                            continue;
                        }
                        if (isInputOverlay && !machineIsInput && !machineId.contains("dual")) {
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Skipping input overlay {} for non-input machine {}",
                                location, machineId);
                            continue;
                        }

                        // Focus on screen/emissive overlays for controllers
                        if (fieldName.contains("screen") || fieldName.contains("emissive") ||
                            locationPath.contains("screen") || locationPath.contains("emissive")) {
                            LOGGER.debug("RecipeFlow GTCEuIconHelper: Found superclass screen/emissive field '{}': {}",
                                field.getName(), location);

                            TextureAtlasSprite sprite = textureAtlas.apply(location);
                            if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                                long frameCount = sprite.contents().getUniqueFrames().count();
                                if (frameCount > 1) {
                                    LOGGER.debug("RecipeFlow GTCEuIconHelper: Adding animated texture from superclass: {} ({} frames)",
                                        location, frameCount);
                                    animatedSprites.add(sprite);
                                }
                            }
                        }
                    }
                }
                superClass = superClass.getSuperclass();
            }

        } catch (Exception e) {
            LOGGER.debug("RecipeFlow GTCEuIconHelper: Error extracting texture fields: {}", e.getMessage());
        }
    }

    /**
     * Try texture path variations based on a model location.
     */
    private static void tryTextureVariations(String basePath, String namespace,
            Function<ResourceLocation, TextureAtlasSprite> textureAtlas,
            Set<TextureAtlasSprite> animatedSprites, String machineId) {

        // Convert model path to potential texture paths
        // e.g., "block/machine/fusion_reactor" -> try various overlay textures
        String[] variations = {
            basePath + "/overlay_front",
            basePath + "/overlay_front_active",
            basePath + "/overlay_front_active_emissive",
            basePath + "_overlay",
            basePath + "_overlay_emissive",
            basePath + "_active",
            basePath + "_active_emissive",
        };

        for (String variation : variations) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(namespace, variation);
            TextureAtlasSprite sprite = textureAtlas.apply(loc);
            if (sprite != null && !sprite.contents().name().getPath().equals("missingno")) {
                long frameCount = sprite.contents().getUniqueFrames().count();
                LOGGER.debug("RecipeFlow GTCEuIconHelper: Model variation '{}': {} ({} frames)",
                    variation, sprite.contents().name(), frameCount);
                if (frameCount > 1) {
                    animatedSprites.add(sprite);
                }
            }
        }
    }

    /**
     * Determine which overlay texture paths apply to a machine based on its ID.
     *
     * Based on texture atlas scan, these are the ANIMATED overlays available:
     * - overlay_pipe_in (6 frames) - GREEN for input machines
     * - overlay_pipe_out (6 frames) - RED for output machines
     * - overlay_item_output (6 frames)
     * - overlay_fluid_output (6 frames)
     * - overlay_buffer / overlay_buffer_emissive (4 frames)
     * - overlay_screen_emissive (4 frames)
     * - overlay_qchest_emissive / overlay_qtank_emissive (4 frames)
     * - overlay_creativecontainer_emissive (14 frames)
     * - overlay_blower_active (4 frames)
     *
     * @param machineId The machine ID path (e.g., "lv_input_bus", "mv_output_hatch")
     * @return List of overlay texture paths to check
     */
    private static List<String> determineOverlayPaths(String machineId) {
        List<String> paths = new ArrayList<>();

        // Normalize for matching
        String normalized = machineId.toLowerCase();

        // Input machines - GREEN overlay (overlay_pipe_in has 6 frames)
        if (normalized.contains("input") || normalized.contains("import")) {
            paths.add(OVERLAY_BASE + "overlay_pipe_in");  // 6 frames, GREEN
        }
        // Output machines - RED overlay (overlay_pipe_out has 6 frames)
        else if (normalized.contains("output") || normalized.contains("export")) {
            paths.add(OVERLAY_BASE + "overlay_pipe_out");  // 6 frames, RED
            paths.add(OVERLAY_BASE + "overlay_item_output");  // 6 frames
            paths.add(OVERLAY_BASE + "overlay_fluid_output");  // 6 frames
        }
        // Dual hatches (both input and output)
        else if (normalized.contains("dual")) {
            paths.add(OVERLAY_BASE + "overlay_pipe_in");
            paths.add(OVERLAY_BASE + "overlay_pipe_out");
        }
        // Buffer machines
        else if (normalized.contains("buffer")) {
            paths.add(OVERLAY_BASE + "overlay_buffer_emissive");  // 4 frames
            paths.add(OVERLAY_BASE + "overlay_buffer");  // 4 frames
        }
        // Quantum chest
        else if (normalized.contains("quantum_chest") || normalized.contains("qchest")) {
            paths.add(OVERLAY_BASE + "overlay_qchest_emissive");  // 4 frames
        }
        // Quantum tank
        else if (normalized.contains("quantum_tank") || normalized.contains("qtank")) {
            paths.add(OVERLAY_BASE + "overlay_qtank_emissive");  // 4 frames
        }
        // Creative container
        else if (normalized.contains("creative")) {
            paths.add(OVERLAY_BASE + "overlay_creativecontainer_emissive");  // 14 frames
        }
        // Default: try common screen overlay for any other machine
        // This includes multiblock controllers which may have screen overlays
        else {
            paths.add(OVERLAY_BASE + "overlay_screen_emissive");  // 4 frames
        }

        return paths;
    }

    /**
     * Debug method to scan all GTCEu overlay textures in the atlas and find animated ones.
     * Call this to discover what animated textures are actually available.
     */
    public static void debugScanOverlayTextures() {
        LOGGER.debug("RecipeFlow GTCEuIconHelper: Scanning for GTCEu overlay textures...");

        Function<ResourceLocation, TextureAtlasSprite> textureAtlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

        // List of known GTCEu overlay texture names to check
        String[] possibleOverlays = {
            // Pipe overlays
            "overlay_pipe", "overlay_pipe_in", "overlay_pipe_out",
            "overlay_pipe_emissive", "overlay_pipe_in_emissive", "overlay_pipe_out_emissive",
            // Item overlays
            "overlay_item_output", "overlay_item_hatch", "overlay_item_hatch_input", "overlay_item_hatch_output",
            // Fluid overlays
            "overlay_fluid_output", "overlay_fluid_hatch", "overlay_fluid_hatch_input", "overlay_fluid_hatch_output",
            // Buffer overlays
            "overlay_buffer", "overlay_buffer_emissive",
            // Screen overlays
            "overlay_screen", "overlay_screen_emissive",
            // Other
            "overlay_blower_active", "overlay_monitor",
            "overlay_qchest_emissive", "overlay_qtank_emissive",
            "overlay_creativecontainer_emissive",
            // Dual hatch
            "overlay_dual_hatch", "overlay_dual_hatch_input", "overlay_dual_hatch_output",
        };

        for (String name : possibleOverlays) {
            String path = OVERLAY_BASE + name;
            ResourceLocation location = ResourceLocation.tryParse(path);
            if (location != null) {
                TextureAtlasSprite sprite = textureAtlas.apply(location);
                if (sprite != null) {
                    String spriteName = sprite.contents().name().toString();
                    long frameCount = sprite.contents().getUniqueFrames().count();
                    boolean isMissing = spriteName.contains("missingno");
                    if (!isMissing) {
                        LOGGER.debug("RecipeFlow GTCEuIconHelper: FOUND {} -> {} ({} frames){}",
                            path, spriteName, frameCount, frameCount > 1 ? " [ANIMATED]" : "");
                    }
                }
            }
        }

        LOGGER.debug("RecipeFlow GTCEuIconHelper: Scan complete.");
    }
}

package com.recipeflow.mod.v120plus.util;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Helper to safely interact with GTCEu's SimpleRenderMode API.
 *
 * SimpleRenderMode has three modes:
 * - DISABLED: Normal GTCEu rendering with emissive effects
 * - INCLUDE_EMISSIVE: Include emissive quads with tint stripped (for animation frame renders)
 * - EXCLUDE_EMISSIVE: Exclude emissive quads entirely (for detection renders)
 *
 * This helper uses reflection to avoid hard dependency on GTCEu at runtime.
 */
@OnlyIn(Dist.CLIENT)
public final class SimpleRenderModeHelper {

    private static final Logger LOGGER = LogManager.getLogger();

    private static boolean initialized = false;
    private static boolean available = false;
    private static boolean hasModeApi = false;

    // Legacy methods (always available if SimpleRenderMode exists)
    private static Method enableMethod = null;
    private static Method disableMethod = null;
    private static Method isEnabledMethod = null;

    // New Mode API methods (available in newer GTCEu versions)
    private static Method setModeMethod = null;
    private static Method getModeMethod = null;
    private static Class<?> modeEnumClass = null;
    private static Object modeDisabled = null;
    private static Object modeIncludeEmissive = null;
    private static Object modeExcludeEmissive = null;

    private SimpleRenderModeHelper() {} // Utility class

    /**
     * Initialize the helper by looking up GTCEu's SimpleRenderMode class.
     */
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Class<?> simpleRenderModeClass = Class.forName(
                "com.gregtechceu.gtceu.api.client.SimpleRenderMode");

            // Look up legacy methods first
            enableMethod = simpleRenderModeClass.getMethod("enable");
            disableMethod = simpleRenderModeClass.getMethod("disable");
            isEnabledMethod = simpleRenderModeClass.getMethod("isEnabled");

            available = true;
            LOGGER.info("RecipeFlow: GTCEu SimpleRenderMode API found and available");

            // Try to find the new Mode API
            try {
                modeEnumClass = Class.forName(
                    "com.gregtechceu.gtceu.api.client.SimpleRenderMode$Mode");

                setModeMethod = simpleRenderModeClass.getMethod("setMode", modeEnumClass);
                getModeMethod = simpleRenderModeClass.getMethod("getMode");

                // Get enum constants
                Object[] enumConstants = modeEnumClass.getEnumConstants();
                for (Object constant : enumConstants) {
                    String name = ((Enum<?>) constant).name();
                    switch (name) {
                        case "DISABLED" -> modeDisabled = constant;
                        case "INCLUDE_EMISSIVE" -> modeIncludeEmissive = constant;
                        case "EXCLUDE_EMISSIVE" -> modeExcludeEmissive = constant;
                    }
                }

                if (modeDisabled != null && modeIncludeEmissive != null && modeExcludeEmissive != null) {
                    hasModeApi = true;
                    LOGGER.info("RecipeFlow: GTCEu SimpleRenderMode Mode API available (DISABLED, INCLUDE_EMISSIVE, EXCLUDE_EMISSIVE)");
                }
            } catch (ClassNotFoundException e) {
                LOGGER.debug("RecipeFlow: GTCEu SimpleRenderMode Mode enum not found (older GTCEu version)");
            } catch (NoSuchMethodException e) {
                LOGGER.debug("RecipeFlow: GTCEu SimpleRenderMode setMode/getMode not found: {}", e.getMessage());
            }

        } catch (ClassNotFoundException e) {
            LOGGER.debug("RecipeFlow: GTCEu SimpleRenderMode not found (GTCEu not installed or old version)");
        } catch (NoSuchMethodException e) {
            LOGGER.warn("RecipeFlow: GTCEu SimpleRenderMode class found but methods missing: {}", e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Error initializing SimpleRenderMode helper: {}", e.getMessage());
        }
    }

    /**
     * Check if SimpleRenderMode API is available.
     */
    public static boolean isAvailable() {
        initialize();
        return available;
    }

    /**
     * Check if the new Mode API (INCLUDE_EMISSIVE/EXCLUDE_EMISSIVE) is available.
     */
    public static boolean hasModeApi() {
        initialize();
        return hasModeApi;
    }

    /**
     * Enable SimpleRenderMode for GTCEu machines (INCLUDE_EMISSIVE mode).
     * When enabled, emissive textures render as normal quads with proper animation.
     */
    public static void enable() {
        initialize();
        if (!available) return;

        try {
            enableMethod.invoke(null);
            LOGGER.debug("RecipeFlow: SimpleRenderMode enabled");
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to enable SimpleRenderMode: {}", e.getMessage());
        }
    }

    /**
     * Disable SimpleRenderMode for GTCEu machines.
     * Returns to normal emissive rendering.
     */
    public static void disable() {
        initialize();
        if (!available) return;

        try {
            disableMethod.invoke(null);
            LOGGER.debug("RecipeFlow: SimpleRenderMode disabled");
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to disable SimpleRenderMode: {}", e.getMessage());
        }
    }

    /**
     * Check if SimpleRenderMode is currently enabled (any mode other than DISABLED).
     */
    public static boolean isEnabled() {
        initialize();
        if (!available) return false;

        try {
            return (Boolean) isEnabledMethod.invoke(null);
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to check SimpleRenderMode state: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Set mode to INCLUDE_EMISSIVE - emissive quads are included with tint stripped.
     * Use this for animation frame renders where you want to capture the emissive overlay.
     */
    public static void setModeIncludeEmissive() {
        initialize();
        if (!hasModeApi) {
            // Fall back to legacy enable() which defaults to including emissive
            enable();
            return;
        }

        try {
            setModeMethod.invoke(null, modeIncludeEmissive);
            LOGGER.debug("RecipeFlow: SimpleRenderMode set to INCLUDE_EMISSIVE");
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to set SimpleRenderMode to INCLUDE_EMISSIVE: {}", e.getMessage());
        }
    }

    /**
     * Set mode to EXCLUDE_EMISSIVE - emissive quads are excluded entirely.
     * Use this for detection renders to identify which pixels are emissive.
     */
    public static void setModeExcludeEmissive() {
        initialize();
        if (!hasModeApi) {
            // No Mode API available - can't exclude emissive, just disable
            LOGGER.debug("RecipeFlow: Mode API not available, using disable() instead of EXCLUDE_EMISSIVE");
            disable();
            return;
        }

        try {
            setModeMethod.invoke(null, modeExcludeEmissive);
            LOGGER.debug("RecipeFlow: SimpleRenderMode set to EXCLUDE_EMISSIVE");
        } catch (Exception e) {
            LOGGER.warn("RecipeFlow: Failed to set SimpleRenderMode to EXCLUDE_EMISSIVE: {}", e.getMessage());
        }
    }

    /**
     * Run a task with SimpleRenderMode enabled, then restore previous state.
     * This is the recommended way to use SimpleRenderMode for capture operations.
     *
     * @param task The task to run with SimpleRenderMode enabled
     */
    public static void runWith(Runnable task) {
        initialize();

        if (!available) {
            // GTCEu not available, just run the task
            task.run();
            return;
        }

        boolean wasEnabled = isEnabled();
        try {
            if (!wasEnabled) {
                enable();
            }
            task.run();
        } finally {
            if (!wasEnabled) {
                disable();
            }
        }
    }

    /**
     * Run a task with SimpleRenderMode enabled and return a result.
     *
     * @param task The task to run with SimpleRenderMode enabled
     * @return The result of the task
     */
    public static <T> T runWith(java.util.function.Supplier<T> task) {
        initialize();

        if (!available) {
            // GTCEu not available, just run the task
            return task.get();
        }

        boolean wasEnabled = isEnabled();
        try {
            if (!wasEnabled) {
                enable();
            }
            return task.get();
        } finally {
            if (!wasEnabled) {
                disable();
            }
        }
    }
}

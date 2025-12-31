package com.recipeflow.mod.v120plus.util;

/**
 * Helper class to hold the FORCE_SHIFT_DOWN flag.
 * This is separate from the Mixin because Mixin doesn't allow non-private static fields,
 * and must be outside the mixin package to be accessible from regular code.
 */
public class ShiftKeyHelper {
    /**
     * Thread-local flag to force hasShiftDown() to return true.
     * Set this to true before calling getTooltipLines() to get shift tooltips.
     */
    public static final ThreadLocal<Boolean> FORCE_SHIFT_DOWN = ThreadLocal.withInitial(() -> false);
}

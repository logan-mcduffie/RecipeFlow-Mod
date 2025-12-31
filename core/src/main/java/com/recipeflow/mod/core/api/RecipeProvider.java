package com.recipeflow.mod.core.api;

import java.util.List;

/**
 * Interface for extracting recipes from a specific source.
 * Each implementation handles a particular mod or recipe system.
 */
public interface RecipeProvider {

    /**
     * Unique identifier for this provider (e.g., "gtceu", "vanilla", "emi")
     */
    String getProviderId();

    /**
     * Human-readable name for logging
     */
    String getProviderName();

    /**
     * Priority for ordering (higher = processed first).
     * Suggested values:
     * - 100: Direct mod API (GTCEu, etc.)
     * - 50: Recipe viewer (EMI, JEI)
     * - 10: Vanilla fallback
     */
    int getPriority();

    /**
     * Check if this provider is available (mod loaded, API accessible)
     */
    boolean isAvailable();

    /**
     * Extract all recipes from this source.
     */
    List<RecipeData> extractRecipes();

    /**
     * Extract all recipes with progress callback for per-mod reporting.
     * Default implementation delegates to extractRecipes().
     *
     * @param callback Callback to report per-mod extraction progress (may be null)
     * @return List of extracted recipes
     */
    default List<RecipeData> extractRecipes(ModExtractionCallback callback) {
        return extractRecipes();
    }

    /**
     * Extract recipes that produce a specific output item.
     *
     * @param itemId The output item ID to search for
     * @return List of recipes producing this item
     */
    List<RecipeData> extractRecipesFor(String itemId);

    /**
     * Callback for reporting per-mod extraction progress.
     */
    interface ModExtractionCallback {
        /**
         * Called when recipes from a specific mod/namespace are extracted.
         *
         * @param modId The mod namespace (e.g., "minecraft", "create", "mekanism")
         * @param count Number of recipes from this mod
         */
        void onModExtracted(String modId, int count);
    }
}

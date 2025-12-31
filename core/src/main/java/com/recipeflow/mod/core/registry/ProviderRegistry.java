package com.recipeflow.mod.core.registry;

import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.api.RecipeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Registry for recipe providers.
 * Manages provider registration, priority ordering, and recipe extraction with deduplication.
 */
public class ProviderRegistry {

    private static final Logger LOGGER = Logger.getLogger(ProviderRegistry.class.getName());

    private final List<RecipeProvider> providers = new ArrayList<>();
    private boolean sorted = false;

    /**
     * Callback for extraction progress reporting.
     */
    public interface ExtractionCallback {
        void onProviderStart(RecipeProvider provider);
        void onProviderComplete(RecipeProvider provider, int recipeCount);
        void onComplete(int totalRecipes, int providers);

        /**
         * Called when recipes from a specific mod/namespace are extracted.
         * Optional - default implementation does nothing.
         *
         * @param provider The provider extracting the recipes
         * @param modId The mod namespace (e.g., "minecraft", "create", "mekanism")
         * @param count Number of recipes from this mod
         */
        default void onModExtracted(RecipeProvider provider, String modId, int count) {
            // Default: do nothing
        }
    }

    /**
     * Register a provider if it's available.
     *
     * @param provider The provider to register
     * @return true if registered successfully
     */
    public boolean register(RecipeProvider provider) {
        if (provider.isAvailable()) {
            providers.add(provider);
            sorted = false;
            LOGGER.info("Registered provider: " + provider.getProviderName() +
                    " (priority " + provider.getPriority() + ")");
            return true;
        } else {
            LOGGER.fine("Provider not available: " + provider.getProviderName());
            return false;
        }
    }

    /**
     * Get all registered providers, sorted by priority (descending).
     */
    public List<RecipeProvider> getProviders() {
        ensureSorted();
        return Collections.unmodifiableList(providers);
    }

    /**
     * Extract all recipes from all providers with deduplication.
     * Higher-priority providers take precedence (first wins).
     *
     * @param callback Optional callback for progress reporting
     * @return List of all unique recipes
     */
    public List<RecipeData> extractAllRecipes(ExtractionCallback callback) {
        ensureSorted();

        // Use LinkedHashMap to preserve insertion order while deduplicating
        Map<String, RecipeData> recipes = new LinkedHashMap<>();

        for (RecipeProvider provider : providers) {
            if (callback != null) {
                callback.onProviderStart(provider);
            }

            try {
                // Create a per-mod callback that forwards to the main callback
                RecipeProvider.ModExtractionCallback modCallback = null;
                if (callback != null) {
                    final RecipeProvider currentProvider = provider;
                    modCallback = (modId, count) -> callback.onModExtracted(currentProvider, modId, count);
                }

                List<RecipeData> extracted = provider.extractRecipes(modCallback);
                int newCount = 0;

                for (RecipeData recipe : extracted) {
                    String id = recipe.getId();
                    if (id != null && !recipes.containsKey(id)) {
                        recipes.put(id, recipe);
                        newCount++;
                    }
                }

                LOGGER.info("Provider " + provider.getProviderName() +
                        " extracted " + extracted.size() + " recipes (" + newCount + " new)");

                if (callback != null) {
                    callback.onProviderComplete(provider, newCount);
                }
            } catch (Exception e) {
                LOGGER.warning("Error extracting from provider " + provider.getProviderName() +
                        ": " + e.getMessage());
            }
        }

        List<RecipeData> result = new ArrayList<>(recipes.values());

        if (callback != null) {
            callback.onComplete(result.size(), providers.size());
        }

        return result;
    }

    /**
     * Extract all recipes without progress callback.
     */
    public List<RecipeData> extractAllRecipes() {
        return extractAllRecipes(null);
    }

    /**
     * Extract recipes for a specific output item from all providers.
     *
     * @param itemId The output item ID
     * @return List of recipes producing this item
     */
    public List<RecipeData> extractRecipesFor(String itemId) {
        ensureSorted();

        Map<String, RecipeData> recipes = new LinkedHashMap<>();

        for (RecipeProvider provider : providers) {
            try {
                List<RecipeData> extracted = provider.extractRecipesFor(itemId);
                for (RecipeData recipe : extracted) {
                    String id = recipe.getId();
                    if (id != null && !recipes.containsKey(id)) {
                        recipes.put(id, recipe);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Error extracting from provider " + provider.getProviderName() +
                        " for item " + itemId + ": " + e.getMessage());
            }
        }

        return new ArrayList<>(recipes.values());
    }

    /**
     * Clear all registered providers.
     */
    public void clear() {
        providers.clear();
        sorted = false;
    }

    /**
     * Get the number of registered providers.
     */
    public int size() {
        return providers.size();
    }

    private void ensureSorted() {
        if (!sorted) {
            providers.sort(Comparator.comparingInt(RecipeProvider::getPriority).reversed());
            sorted = true;
        }
    }
}

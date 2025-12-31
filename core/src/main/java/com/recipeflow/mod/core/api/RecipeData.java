package com.recipeflow.mod.core.api;

import java.util.Map;

/**
 * Base class for all recipe data models.
 * Designed to serialize to JSON matching the TypeScript API types.
 */
public abstract class RecipeData {

    protected String id;
    protected String type;
    protected String sourceMod;

    protected RecipeData() {
    }

    protected RecipeData(String id, String type, String sourceMod) {
        this.id = id;
        this.type = type;
        this.sourceMod = sourceMod;
    }

    /**
     * Unique recipe identifier (e.g., "gtceu:chemical_reactor/sodium_hydroxide")
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * Recipe type (e.g., "minecraft:crafting_shaped", "gregtech:machine")
     */
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * Source mod that provides this recipe (e.g., "minecraft", "gtceu")
     */
    public String getSourceMod() {
        return sourceMod;
    }

    public void setSourceMod(String sourceMod) {
        this.sourceMod = sourceMod;
    }

    /**
     * Convert to JSON-serializable map matching API contract.
     * This is the data that goes into the Recipe.data JSONB field.
     */
    public abstract Map<String, Object> toJsonMap();

    /**
     * Get the output item ID for indexing purposes.
     * Used for the Recipe.itemId column.
     */
    public abstract String getOutputItemId();
}

package com.recipeflow.mod.core.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.recipeflow.mod.core.api.RecipeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON serializer for recipe data.
 * Produces output matching the TypeScript API types.
 */
public class RecipeSerializer {

    private final Gson gson;
    private final Gson prettyGson;

    public RecipeSerializer() {
        this.gson = new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .create();

        this.prettyGson = new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Serialize a single recipe to JSON.
     *
     * @param recipe The recipe to serialize
     * @return JSON string
     */
    public String serialize(RecipeData recipe) {
        return gson.toJson(recipe.toJsonMap());
    }

    /**
     * Serialize a single recipe to pretty-printed JSON.
     *
     * @param recipe The recipe to serialize
     * @return Pretty-printed JSON string
     */
    public String serializePretty(RecipeData recipe) {
        return prettyGson.toJson(recipe.toJsonMap());
    }

    /**
     * Serialize a list of recipes to JSON array.
     *
     * @param recipes The recipes to serialize
     * @return JSON array string
     */
    public String serializeAll(List<RecipeData> recipes) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (RecipeData recipe : recipes) {
            maps.add(recipe.toJsonMap());
        }
        return gson.toJson(maps);
    }

    /**
     * Serialize a list of recipes to pretty-printed JSON array.
     *
     * @param recipes The recipes to serialize
     * @return Pretty-printed JSON array string
     */
    public String serializeAllPretty(List<RecipeData> recipes) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (RecipeData recipe : recipes) {
            maps.add(recipe.toJsonMap());
        }
        return prettyGson.toJson(maps);
    }

    /**
     * Serialize a recipe to a full record format for API sync.
     * Matches RecipeSyncInput interface:
     * - recipeId: string
     * - type: string
     * - sourceMod: string
     * - data: Record<string, unknown>
     *
     * @param recipe The recipe to serialize
     * @return Map with recipeId, type, sourceMod, and data fields
     */
    public Map<String, Object> toRecordMap(RecipeData recipe) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("recipeId", recipe.getId());
        record.put("type", recipe.getType());
        record.put("sourceMod", recipe.getSourceMod() != null ? recipe.getSourceMod() : "unknown");
        record.put("data", recipe.toJsonMap());
        return record;
    }

    /**
     * Serialize recipes to a full records format for API sync.
     *
     * @param recipes The recipes to serialize
     * @return JSON string with array of records
     */
    public String serializeForSync(List<RecipeData> recipes) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (RecipeData recipe : recipes) {
            records.add(toRecordMap(recipe));
        }
        return gson.toJson(records);
    }

    /**
     * Get the underlying Gson instance for custom serialization needs.
     */
    public Gson getGson() {
        return gson;
    }
}

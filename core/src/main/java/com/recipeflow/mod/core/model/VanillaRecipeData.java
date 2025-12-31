package com.recipeflow.mod.core.model;

import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe data for vanilla Minecraft recipes.
 * Matches TypeScript vanilla recipe interfaces.
 */
public class VanillaRecipeData extends RecipeData {

    /**
     * Vanilla recipe types.
     */
    public enum VanillaRecipeType {
        CRAFTING_SHAPED("minecraft:crafting_shaped"),
        CRAFTING_SHAPELESS("minecraft:crafting_shapeless"),
        SMELTING("minecraft:smelting"),
        BLASTING("minecraft:blasting"),
        SMOKING("minecraft:smoking"),
        CAMPFIRE_COOKING("minecraft:campfire_cooking"),
        STONECUTTING("minecraft:stonecutting"),
        SMITHING_TRANSFORM("minecraft:smithing_transform"),
        SMITHING_TRIM("minecraft:smithing_trim");

        private final String typeId;

        VanillaRecipeType(String typeId) {
            this.typeId = typeId;
        }

        public String getTypeId() {
            return typeId;
        }
    }

    private VanillaRecipeType recipeType;

    // For shaped crafting
    private String[] pattern;
    private Map<String, ItemStackData> key;

    // For shapeless crafting
    private List<ItemStackData> ingredients;

    // For smelting-type recipes
    private ItemStackData input;
    private double experience;
    private int cookingTime;

    // For smithing recipes
    private ItemStackData template;
    private ItemStackData base;
    private ItemStackData addition;

    // Output (all types)
    private ItemStackData output;

    public VanillaRecipeData() {
        this.sourceMod = "minecraft";
    }

    public VanillaRecipeData(VanillaRecipeType recipeType) {
        this();
        setRecipeType(recipeType);
    }

    public VanillaRecipeType getRecipeType() {
        return recipeType;
    }

    public void setRecipeType(VanillaRecipeType recipeType) {
        this.recipeType = recipeType;
        this.type = recipeType.getTypeId();
    }

    public String[] getPattern() {
        return pattern;
    }

    public void setPattern(String[] pattern) {
        this.pattern = pattern;
    }

    public Map<String, ItemStackData> getKey() {
        return key;
    }

    public void setKey(Map<String, ItemStackData> key) {
        this.key = key;
    }

    public void addKey(String symbol, ItemStackData item) {
        if (key == null) {
            key = new LinkedHashMap<>();
        }
        key.put(symbol, item);
    }

    public List<ItemStackData> getIngredients() {
        return ingredients;
    }

    public void setIngredients(List<ItemStackData> ingredients) {
        this.ingredients = ingredients;
    }

    public void addIngredient(ItemStackData ingredient) {
        if (ingredients == null) {
            ingredients = new ArrayList<>();
        }
        ingredients.add(ingredient);
    }

    public ItemStackData getInput() {
        return input;
    }

    public void setInput(ItemStackData input) {
        this.input = input;
    }

    public double getExperience() {
        return experience;
    }

    public void setExperience(double experience) {
        this.experience = experience;
    }

    public int getCookingTime() {
        return cookingTime;
    }

    public void setCookingTime(int cookingTime) {
        this.cookingTime = cookingTime;
    }

    public ItemStackData getTemplate() {
        return template;
    }

    public void setTemplate(ItemStackData template) {
        this.template = template;
    }

    public ItemStackData getBase() {
        return base;
    }

    public void setBase(ItemStackData base) {
        this.base = base;
    }

    public ItemStackData getAddition() {
        return addition;
    }

    public void setAddition(ItemStackData addition) {
        this.addition = addition;
    }

    public ItemStackData getOutput() {
        return output;
    }

    public void setOutput(ItemStackData output) {
        this.output = output;
    }

    @Override
    public String getOutputItemId() {
        return output != null ? output.getItemId() : null;
    }

    @Override
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);

        switch (recipeType) {
            case CRAFTING_SHAPED:
                map.put("pattern", pattern);
                if (key != null) {
                    Map<String, Object> keyMap = new LinkedHashMap<>();
                    for (Map.Entry<String, ItemStackData> entry : key.entrySet()) {
                        keyMap.put(entry.getKey(), entry.getValue().toJsonMap());
                    }
                    map.put("key", keyMap);
                }
                map.put("output", output.toJsonMap());
                break;

            case CRAFTING_SHAPELESS:
                if (ingredients != null) {
                    List<Map<String, Object>> ingredientMaps = new ArrayList<>();
                    for (ItemStackData item : ingredients) {
                        ingredientMaps.add(item.toJsonMap());
                    }
                    map.put("ingredients", ingredientMaps);
                }
                map.put("output", output.toJsonMap());
                break;

            case SMELTING:
            case BLASTING:
            case SMOKING:
            case CAMPFIRE_COOKING:
                map.put("input", input.toJsonMap());
                map.put("output", output.toJsonMap());
                map.put("experience", experience);
                map.put("cookingTime", cookingTime);
                break;

            case STONECUTTING:
                map.put("input", input.toJsonMap());
                map.put("output", output.toJsonMap());
                break;

            case SMITHING_TRANSFORM:
            case SMITHING_TRIM:
                if (template != null) {
                    map.put("template", template.toJsonMap());
                }
                if (base != null) {
                    map.put("base", base.toJsonMap());
                }
                if (addition != null) {
                    map.put("addition", addition.toJsonMap());
                }
                if (output != null) {
                    map.put("output", output.toJsonMap());
                }
                break;
        }

        return map;
    }
}

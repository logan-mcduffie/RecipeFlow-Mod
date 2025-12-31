package com.recipeflow.mod.v120plus.provider;

import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.api.RecipeProvider;
import com.recipeflow.mod.core.model.VanillaRecipeData;
import com.recipeflow.mod.core.model.VanillaRecipeData.VanillaRecipeType;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Recipe provider that extracts recipes from Minecraft's standard RecipeManager.
 * This covers all mods that register recipes through the standard recipe system
 * (JSON recipes, crafting, smelting, etc.).
 *
 * Priority 10 - Base provider for standard recipes.
 *
 * Note: GTCEu recipes are excluded since they are handled by GTCEuRecipeProvider
 * which provides richer data (EU/t, duration, conditions, etc.).
 */
public class StandardRecipeProvider implements RecipeProvider {

    private static final Logger LOGGER = Logger.getLogger(StandardRecipeProvider.class.getName());

    // Namespaces to skip (handled by specialized providers)
    private static final java.util.Set<String> EXCLUDED_NAMESPACES = java.util.Set.of("gtceu");

    /**
     * Check if a recipe should be excluded based on its namespace.
     */
    private boolean shouldExclude(ResourceLocation recipeId) {
        return recipeId != null && EXCLUDED_NAMESPACES.contains(recipeId.getNamespace());
    }

    @Override
    public String getProviderId() {
        return "standard";
    }

    @Override
    public String getProviderName() {
        return "Standard Recipes";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean isAvailable() {
        return ServerLifecycleHooks.getCurrentServer() != null;
    }

    @Override
    public List<RecipeData> extractRecipes() {
        return extractRecipes(null);
    }

    @Override
    public List<RecipeData> extractRecipes(ModExtractionCallback callback) {
        List<RecipeData> results = new ArrayList<>();

        if (!isAvailable()) {
            return results;
        }

        try {
            RecipeManager recipeManager = ServerLifecycleHooks.getCurrentServer().getRecipeManager();
            RegistryAccess registryAccess = ServerLifecycleHooks.getCurrentServer().registryAccess();

            // Crafting recipes
            results.addAll(extractCraftingRecipes(recipeManager, registryAccess));

            // Smelting recipes
            results.addAll(extractSmeltingRecipes(recipeManager, registryAccess, RecipeType.SMELTING, VanillaRecipeType.SMELTING));
            results.addAll(extractSmeltingRecipes(recipeManager, registryAccess, RecipeType.BLASTING, VanillaRecipeType.BLASTING));
            results.addAll(extractSmeltingRecipes(recipeManager, registryAccess, RecipeType.SMOKING, VanillaRecipeType.SMOKING));
            results.addAll(extractSmeltingRecipes(recipeManager, registryAccess, RecipeType.CAMPFIRE_COOKING, VanillaRecipeType.CAMPFIRE_COOKING));

            // Stonecutting
            results.addAll(extractStonecuttingRecipes(recipeManager, registryAccess));

            // Smithing
            results.addAll(extractSmithingRecipes(recipeManager, registryAccess));

            LOGGER.info("Extracted " + results.size() + " standard recipes");

            // Report per-mod breakdown if callback provided
            if (callback != null) {
                reportPerModCounts(results, callback);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to extract standard recipes: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    /**
     * Count recipes by source mod and report via callback.
     */
    private void reportPerModCounts(List<RecipeData> recipes, ModExtractionCallback callback) {
        // Count recipes per mod
        Map<String, Integer> modCounts = new HashMap<>();
        for (RecipeData recipe : recipes) {
            String modId = recipe.getSourceMod();
            if (modId == null || modId.isEmpty()) {
                modId = "unknown";
            }
            modCounts.merge(modId, 1, Integer::sum);
        }

        // Sort by count descending, then by name
        modCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> callback.onModExtracted(entry.getKey(), entry.getValue()));
    }

    @Override
    public List<RecipeData> extractRecipesFor(String itemId) {
        List<RecipeData> all = extractRecipes();
        List<RecipeData> filtered = new ArrayList<>();

        for (RecipeData recipe : all) {
            if (itemId.equals(recipe.getOutputItemId())) {
                filtered.add(recipe);
            }
        }

        return filtered;
    }

    private List<RecipeData> extractCraftingRecipes(RecipeManager recipeManager, RegistryAccess registryAccess) {
        List<RecipeData> results = new ArrayList<>();

        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                // Skip recipes from excluded namespaces (e.g., gtceu)
                if (shouldExclude(recipe.getId())) {
                    continue;
                }

                if (recipe instanceof ShapedRecipe) {
                    results.add(convertShapedRecipe((ShapedRecipe) recipe, registryAccess));
                } else if (recipe instanceof ShapelessRecipe) {
                    results.add(convertShapelessRecipe((ShapelessRecipe) recipe, registryAccess));
                }
            } catch (Exception e) {
                LOGGER.fine("Failed to convert crafting recipe: " + e.getMessage());
            }
        }

        return results;
    }

    private VanillaRecipeData convertShapedRecipe(ShapedRecipe recipe, RegistryAccess registryAccess) {
        VanillaRecipeData data = new VanillaRecipeData(VanillaRecipeType.CRAFTING_SHAPED);
        data.setId(recipe.getId().toString());
        // Set sourceMod from recipe ID namespace (e.g., "create:brass_casing" -> "create")
        data.setSourceMod(recipe.getId().getNamespace());

        // Get pattern
        int width = recipe.getWidth();
        int height = recipe.getHeight();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        // Build pattern and key
        String[] pattern = new String[height];
        Map<String, ItemStackData> key = new LinkedHashMap<>();
        Map<String, Character> ingredientToChar = new LinkedHashMap<>();
        char currentChar = 'A';

        for (int row = 0; row < height; row++) {
            StringBuilder rowPattern = new StringBuilder();

            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                Ingredient ingredient = index < ingredients.size() ? ingredients.get(index) : Ingredient.EMPTY;

                if (ingredient.isEmpty()) {
                    rowPattern.append(' ');
                } else {
                    // Get a unique character for this ingredient
                    String ingredientKey = getIngredientKey(ingredient);
                    Character c = ingredientToChar.get(ingredientKey);

                    if (c == null) {
                        c = currentChar++;
                        ingredientToChar.put(ingredientKey, c);

                        // Add to key map
                        ItemStack[] stacks = ingredient.getItems();
                        if (stacks.length > 0) {
                            key.put(String.valueOf(c), convertItemStack(stacks[0]));
                        }
                    }

                    rowPattern.append(c);
                }
            }

            pattern[row] = rowPattern.toString();
        }

        data.setPattern(pattern);
        data.setKey(key);

        // Output
        ItemStack output = recipe.getResultItem(registryAccess);
        data.setOutput(convertItemStack(output));

        return data;
    }

    private VanillaRecipeData convertShapelessRecipe(ShapelessRecipe recipe, RegistryAccess registryAccess) {
        VanillaRecipeData data = new VanillaRecipeData(VanillaRecipeType.CRAFTING_SHAPELESS);
        data.setId(recipe.getId().toString());
        data.setSourceMod(recipe.getId().getNamespace());

        // Ingredients
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] stacks = ingredient.getItems();
            if (stacks.length > 0) {
                data.addIngredient(convertItemStack(stacks[0]));
            }
        }

        // Output
        ItemStack output = recipe.getResultItem(registryAccess);
        data.setOutput(convertItemStack(output));

        return data;
    }

    private List<RecipeData> extractSmeltingRecipes(
            RecipeManager recipeManager,
            RegistryAccess registryAccess,
            RecipeType<? extends AbstractCookingRecipe> recipeType,
            VanillaRecipeType vanillaType) {

        List<RecipeData> results = new ArrayList<>();

        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(recipeType)) {
            try {
                // Skip recipes from excluded namespaces (e.g., gtceu)
                if (shouldExclude(recipe.getId())) {
                    continue;
                }

                if (recipe instanceof AbstractCookingRecipe) {
                    AbstractCookingRecipe cookingRecipe = (AbstractCookingRecipe) recipe;

                    VanillaRecipeData data = new VanillaRecipeData(vanillaType);
                    data.setId(recipe.getId().toString());
                    data.setSourceMod(recipe.getId().getNamespace());

                    // Input
                    NonNullList<Ingredient> ingredients = cookingRecipe.getIngredients();
                    if (!ingredients.isEmpty()) {
                        ItemStack[] stacks = ingredients.get(0).getItems();
                        if (stacks.length > 0) {
                            data.setInput(convertItemStack(stacks[0]));
                        }
                    }

                    // Output
                    ItemStack output = cookingRecipe.getResultItem(registryAccess);
                    data.setOutput(convertItemStack(output));

                    // Experience and cooking time
                    data.setExperience(cookingRecipe.getExperience());
                    data.setCookingTime(cookingRecipe.getCookingTime());

                    results.add(data);
                }
            } catch (Exception e) {
                LOGGER.fine("Failed to convert smelting recipe: " + e.getMessage());
            }
        }

        return results;
    }

    private List<RecipeData> extractStonecuttingRecipes(RecipeManager recipeManager, RegistryAccess registryAccess) {
        List<RecipeData> results = new ArrayList<>();

        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.STONECUTTING)) {
            try {
                // Skip recipes from excluded namespaces (e.g., gtceu)
                if (shouldExclude(recipe.getId())) {
                    continue;
                }

                if (recipe instanceof StonecutterRecipe) {
                    StonecutterRecipe stonecutterRecipe = (StonecutterRecipe) recipe;

                    VanillaRecipeData data = new VanillaRecipeData(VanillaRecipeType.STONECUTTING);
                    data.setId(recipe.getId().toString());
                    data.setSourceMod(recipe.getId().getNamespace());

                    // Input
                    NonNullList<Ingredient> ingredients = stonecutterRecipe.getIngredients();
                    if (!ingredients.isEmpty()) {
                        ItemStack[] stacks = ingredients.get(0).getItems();
                        if (stacks.length > 0) {
                            data.setInput(convertItemStack(stacks[0]));
                        }
                    }

                    // Output
                    ItemStack output = stonecutterRecipe.getResultItem(registryAccess);
                    data.setOutput(convertItemStack(output));

                    results.add(data);
                }
            } catch (Exception e) {
                LOGGER.fine("Failed to convert stonecutting recipe: " + e.getMessage());
            }
        }

        return results;
    }

    private List<RecipeData> extractSmithingRecipes(RecipeManager recipeManager, RegistryAccess registryAccess) {
        List<RecipeData> results = new ArrayList<>();

        for (Recipe<?> recipe : recipeManager.getAllRecipesFor(RecipeType.SMITHING)) {
            try {
                // Skip recipes from excluded namespaces (e.g., gtceu)
                if (shouldExclude(recipe.getId())) {
                    continue;
                }

                if (recipe instanceof SmithingTransformRecipe) {
                    SmithingTransformRecipe smithingRecipe = (SmithingTransformRecipe) recipe;

                    VanillaRecipeData data = new VanillaRecipeData(VanillaRecipeType.SMITHING_TRANSFORM);
                    data.setId(recipe.getId().toString());
                    data.setSourceMod(recipe.getId().getNamespace());

                    // Template, base, and addition are accessible via reflection or the recipe itself
                    // For now, we'll use a simplified approach
                    NonNullList<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.size() >= 3) {
                        ItemStack[] templateStacks = ingredients.get(0).getItems();
                        ItemStack[] baseStacks = ingredients.get(1).getItems();
                        ItemStack[] additionStacks = ingredients.get(2).getItems();

                        if (templateStacks.length > 0) {
                            data.setTemplate(convertItemStack(templateStacks[0]));
                        }
                        if (baseStacks.length > 0) {
                            data.setBase(convertItemStack(baseStacks[0]));
                        }
                        if (additionStacks.length > 0) {
                            data.setAddition(convertItemStack(additionStacks[0]));
                        }
                    }

                    // Output
                    ItemStack output = smithingRecipe.getResultItem(registryAccess);
                    data.setOutput(convertItemStack(output));

                    results.add(data);
                } else if (recipe instanceof SmithingTrimRecipe) {
                    // Trim recipes don't have a traditional output
                    // They modify the input item's trim
                    VanillaRecipeData data = new VanillaRecipeData(VanillaRecipeType.SMITHING_TRIM);
                    data.setId(recipe.getId().toString());
                    data.setSourceMod(recipe.getId().getNamespace());

                    NonNullList<Ingredient> ingredients = recipe.getIngredients();
                    if (ingredients.size() >= 3) {
                        ItemStack[] templateStacks = ingredients.get(0).getItems();
                        ItemStack[] baseStacks = ingredients.get(1).getItems();
                        ItemStack[] additionStacks = ingredients.get(2).getItems();

                        if (templateStacks.length > 0) {
                            data.setTemplate(convertItemStack(templateStacks[0]));
                        }
                        if (baseStacks.length > 0) {
                            data.setBase(convertItemStack(baseStacks[0]));
                            // For trim recipes, output is the base item (modified)
                            data.setOutput(convertItemStack(baseStacks[0]));
                        }
                        if (additionStacks.length > 0) {
                            data.setAddition(convertItemStack(additionStacks[0]));
                        }
                    }

                    results.add(data);
                }
            } catch (Exception e) {
                LOGGER.fine("Failed to convert smithing recipe: " + e.getMessage());
            }
        }

        return results;
    }

    private ItemStackData convertItemStack(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String id = itemId != null ? itemId.toString() : "minecraft:air";
        return new ItemStackData(id, stack.getCount());
    }

    private String getIngredientKey(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) {
            return "empty";
        }

        // Use first item's registry name as key
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stacks[0].getItem());
        return id != null ? id.toString() : "unknown";
    }
}

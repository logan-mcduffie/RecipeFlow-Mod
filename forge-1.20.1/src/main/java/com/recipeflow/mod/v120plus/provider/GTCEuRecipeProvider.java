package com.recipeflow.mod.v120plus.provider;

import com.recipeflow.mod.core.api.FluidStackData;
import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.api.RecipeProvider;
import com.recipeflow.mod.core.model.GregTechRecipeData;
import com.recipeflow.mod.core.model.GregTechRecipeData.ChancedItemOutput;
import com.recipeflow.mod.core.model.GregTechRecipeData.SpecialConditions;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gregtechceu.gtceu.api.recipe.lookup.GTRecipeLookup;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Recipe provider for GregTech CEu Modern (1.20.1+).
 * Priority 100 - Direct API access for accurate GT recipe data.
 */
public class GTCEuRecipeProvider implements RecipeProvider {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String GTCEU_MOD_ID = "gtceu";

    @Override
    public String getProviderId() {
        return "gtceu";
    }

    @Override
    public String getProviderName() {
        return "GregTech CEu Modern";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean isAvailable() {
        return ModList.get().isLoaded(GTCEU_MOD_ID);
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
            results = extractGTRecipesInternal(callback);
        } catch (Throwable e) {
            // Log at ERROR level so it shows in console
            LOGGER.error("RecipeFlow: Failed to extract GTCEu recipes: {} - {}",
                    e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    @Override
    public List<RecipeData> extractRecipesFor(String itemId) {
        // Extract all and filter - this method exists for interface compliance
        // but the mod's primary use case is bulk export via extractRecipes()
        List<RecipeData> all = extractRecipes();
        List<RecipeData> filtered = new ArrayList<>();

        for (RecipeData recipe : all) {
            if (itemId.equals(recipe.getOutputItemId())) {
                filtered.add(recipe);
            }
        }

        return filtered;
    }

    /**
     * Internal method that uses GTCEu classes directly.
     * This is isolated so class loading only happens when GTCEu is present.
     *
     * Uses the GTRecipeLookup branch tree to iterate all recipes efficiently.
     * See: https://github.com/GregTechCEu/GregTech-Modern/pull/3986
     */
    private List<RecipeData> extractGTRecipesInternal(ModExtractionCallback callback) {
        List<RecipeData> results = new ArrayList<>();

        LOGGER.info("RecipeFlow: Extracting GTCEu recipes via lookup tree...");

        int typeCount = 0;
        int totalRecipes = 0;

        // Collect per-machine-type counts for reporting
        java.util.Map<String, Integer> machineTypeCounts = new java.util.LinkedHashMap<>();

        // Iterate all registered recipe types
        for (GTRecipeType recipeType : GTRegistries.RECIPE_TYPES) {
            typeCount++;
            String machineType = recipeType.registryName.getPath();

            try {
                // Get all recipes from the lookup tree
                // This traverses all branches to find every recipe
                GTRecipeLookup lookup = recipeType.getLookup();
                Branch rootBranch = lookup.getLookup();

                // getRecipes(true) recursively gets all recipes from all branches
                Stream<GTRecipe> recipeStream = rootBranch.getRecipes(true);

                int typeRecipeCount = 0;
                for (GTRecipe recipe : (Iterable<GTRecipe>) recipeStream::iterator) {
                    try {
                        GregTechRecipeData data = convertGTRecipe(recipe, machineType);
                        if (data != null && data.hasOutputs()) {
                            results.add(data);
                            totalRecipes++;
                            typeRecipeCount++;
                        }
                    } catch (Exception e) {
                        LOGGER.trace("RecipeFlow: Failed to convert GT recipe {}: {}",
                                recipe.id, e.getMessage());
                    }
                }

                if (typeRecipeCount > 0) {
                    machineTypeCounts.put(machineType, typeRecipeCount);
                    LOGGER.debug("RecipeFlow: {} -> {} recipes", machineType, typeRecipeCount);
                }
            } catch (Exception e) {
                LOGGER.warn("RecipeFlow: Error extracting from GT recipe type {}: {}",
                        machineType, e.getMessage());
                LOGGER.debug("RecipeFlow: GT recipe type error stack:", e);
            }
        }

        // Report per-machine-type counts via callback (sorted by count descending)
        if (callback != null) {
            machineTypeCounts.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue(java.util.Comparator.reverseOrder()))
                    .forEach(entry -> callback.onModExtracted("gtceu:" + entry.getKey(), entry.getValue()));
        }

        LOGGER.info("RecipeFlow: Processed {} GT recipe types, extracted {} total recipes",
                typeCount, totalRecipes);
        return results;
    }

    private GregTechRecipeData convertGTRecipe(GTRecipe recipe, String machineType) {
        GregTechRecipeData data = new GregTechRecipeData();

        // Basic recipe info
        data.setId(recipe.id.toString());
        // Use the recipe's namespace to properly attribute addon recipes (e.g., star_technology)
        data.setSourceMod(recipe.id.getNamespace());
        data.setMachineType(machineType);
        data.setDuration(recipe.duration);

        // Get EU/t using RecipeHelper
        long euPerTick = RecipeHelper.getInputEUt(recipe);
        data.setEuPerTick((int) euPerTick);

        // Extract item inputs
        extractItemInputs(recipe, data);

        // Extract fluid inputs
        extractFluidInputs(recipe, data);

        // Extract item outputs (with chance info)
        extractItemOutputs(recipe, data);

        // Extract fluid outputs
        extractFluidOutputs(recipe, data);

        // Extract conditions
        extractConditions(recipe, data);

        return data;
    }

    private void extractItemInputs(GTRecipe recipe, GregTechRecipeData data) {
        List<Content> contents = recipe.getInputContents(ItemRecipeCapability.CAP);
        if (contents == null) return;

        for (Content content : contents) {
            Object ingredient = content.content;

            // Check if this is an IntCircuitIngredient (programmed circuit)
            if (isIntCircuitIngredient(ingredient)) {
                int circuitNumber = extractCircuitNumber(ingredient);
                if (circuitNumber > 0) {
                    data.setCircuit(circuitNumber);
                }
                continue;
            }

            // Handle Ingredient or SizedIngredient
            ItemStack[] stacks = getItemStacks(ingredient);
            if (stacks != null && stacks.length > 0) {
                ItemStack stack = stacks[0];
                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());

                if (itemId != null) {
                    // Check if this input is NOT consumed (molds, lenses, etc.)
                    // In GTCEu, non-consumed inputs have chance = 0 (0% chance to be consumed)
                    boolean isNotConsumed = content.chance == 0;

                    if (isNotConsumed) {
                        data.addInputItem(ItemStackData.notConsumed(
                                itemId.toString(),
                                stack.getCount()
                        ));
                    } else {
                        data.addInputItem(new ItemStackData(
                                itemId.toString(),
                                stack.getCount()
                        ));
                    }
                }
            }
        }
    }

    private void extractFluidInputs(GTRecipe recipe, GregTechRecipeData data) {
        List<Content> contents = recipe.getInputContents(FluidRecipeCapability.CAP);
        if (contents == null) return;

        for (Content content : contents) {
            FluidStack fluidStack = getFluidStack(content.content);

            if (fluidStack != null && !fluidStack.isEmpty()) {
                ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid());

                if (fluidId != null) {
                    data.addInputFluid(new FluidStackData(
                            fluidId.toString(),
                            fluidStack.getAmount()
                    ));
                }
            }
        }
    }

    private void extractItemOutputs(GTRecipe recipe, GregTechRecipeData data) {
        List<Content> contents = recipe.getOutputContents(ItemRecipeCapability.CAP);
        if (contents == null) return;

        for (Content content : contents) {
            Object ingredient = content.content;

            ItemStack[] stacks = getItemStacks(ingredient);
            if (stacks != null && stacks.length > 0) {
                ItemStack stack = stacks[0];
                ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());

                if (itemId != null) {
                    // Chance is stored as int (out of maxChance, typically 10000)
                    // Convert to 0.0-1.0 range
                    Double chanceValue = null;
                    Double boostValue = null;

                    if (content.maxChance > 0 && content.chance < content.maxChance) {
                        chanceValue = (double) content.chance / content.maxChance;
                    }
                    if (content.tierChanceBoost > 0 && content.maxChance > 0) {
                        boostValue = (double) content.tierChanceBoost / content.maxChance;
                    }

                    ChancedItemOutput output = new ChancedItemOutput(
                            itemId.toString(),
                            stack.getCount(),
                            chanceValue,
                            boostValue
                    );

                    data.addOutputItem(output);
                }
            }
        }
    }

    private void extractFluidOutputs(GTRecipe recipe, GregTechRecipeData data) {
        List<Content> contents = recipe.getOutputContents(FluidRecipeCapability.CAP);
        if (contents == null) return;

        for (Content content : contents) {
            FluidStack fluidStack = getFluidStack(content.content);

            if (fluidStack != null && !fluidStack.isEmpty()) {
                ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(fluidStack.getFluid());

                if (fluidId != null) {
                    data.addOutputFluid(new FluidStackData(
                            fluidId.toString(),
                            fluidStack.getAmount()
                    ));
                }
            }
        }
    }

    private void extractConditions(GTRecipe recipe, GregTechRecipeData data) {
        List<RecipeCondition> conditions = recipe.conditions;
        if (conditions == null || conditions.isEmpty()) {
            return;
        }

        SpecialConditions special = data.getOrCreateSpecialConditions();

        for (RecipeCondition condition : conditions) {
            String type = condition.getClass().getSimpleName();

            switch (type) {
                case "CleanroomCondition":
                    special.setCleanroom(true);
                    break;
                case "VacuumCondition":
                    special.setVacuum(true);
                    break;
                case "CoilCondition":
                    try {
                        // Try to get coil tier from the condition
                        int coilTier = (int) condition.getClass()
                                .getMethod("getCoilTier")
                                .invoke(condition);
                        special.setCoilTier(coilTier);
                    } catch (Exception ignored) {
                        // Coil tier method might not exist
                    }
                    break;
                default:
                    // Store other conditions as extra
                    special.addExtra(type, true);
                    break;
            }
        }
    }

    /**
     * Get ItemStack array from an ingredient (handles Ingredient and SizedIngredient).
     */
    private ItemStack[] getItemStacks(Object ingredient) {
        if (ingredient instanceof Ingredient) {
            return ((Ingredient) ingredient).getItems();
        }

        // Handle SizedIngredient or other custom ingredient types
        try {
            return (ItemStack[]) ingredient.getClass().getMethod("getItems").invoke(ingredient);
        } catch (Exception e1) {
            try {
                // Some ingredients use getItemStacks() instead
                @SuppressWarnings("unchecked")
                List<ItemStack> list = (List<ItemStack>) ingredient.getClass()
                        .getMethod("getItemStacks")
                        .invoke(ingredient);
                return list.toArray(new ItemStack[0]);
            } catch (Exception e2) {
                LOGGER.trace("RecipeFlow: Could not get item stacks from {}: {}",
                        ingredient.getClass().getSimpleName(), e2.getMessage());
                return null;
            }
        }
    }

    /**
     * Get FluidStack from a fluid ingredient.
     */
    private FluidStack getFluidStack(Object fluidIngredient) {
        if (fluidIngredient instanceof FluidStack) {
            return (FluidStack) fluidIngredient;
        }

        // Handle FluidIngredient types
        try {
            @SuppressWarnings("unchecked")
            List<FluidStack> stacks = (List<FluidStack>) fluidIngredient.getClass()
                    .getMethod("getStacks")
                    .invoke(fluidIngredient);
            if (!stacks.isEmpty()) {
                return stacks.get(0);
            }
        } catch (Exception e1) {
            try {
                FluidStack[] stacks = (FluidStack[]) fluidIngredient.getClass()
                        .getMethod("getFluids")
                        .invoke(fluidIngredient);
                if (stacks.length > 0) {
                    return stacks[0];
                }
            } catch (Exception e2) {
                LOGGER.trace("RecipeFlow: Could not get fluid stack from {}: {}",
                        fluidIngredient.getClass().getSimpleName(), e2.getMessage());
            }
        }
        return null;
    }

    private boolean isIntCircuitIngredient(Object ingredient) {
        String className = ingredient.getClass().getName();
        return className.contains("IntCircuit") || className.contains("IntProviderIngredient");
    }

    private int extractCircuitNumber(Object ingredient) {
        try {
            return (int) ingredient.getClass().getMethod("getCircuitNumber").invoke(ingredient);
        } catch (Exception e1) {
            try {
                return (int) ingredient.getClass().getMethod("getConfiguration").invoke(ingredient);
            } catch (Exception e2) {
                try {
                    return (int) ingredient.getClass().getField("configuration").get(ingredient);
                } catch (Exception e3) {
                    LOGGER.trace("RecipeFlow: Could not extract circuit number: {}", e3.getMessage());
                    return 0;
                }
            }
        }
    }
}

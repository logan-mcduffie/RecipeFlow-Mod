package com.recipeflow.mod.v120plus.util;

import com.recipeflow.mod.core.api.FluidStackData;
import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;
import com.recipeflow.mod.core.model.GregTechRecipeData;
import com.recipeflow.mod.core.model.ItemMetadata;
import com.recipeflow.mod.core.model.VanillaRecipeData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts item metadata (display names and tooltips) from recipes.
 * Must run on client side to access localized strings and tooltip rendering.
 */
@OnlyIn(Dist.CLIENT)
public class ItemMetadataExtractor {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Callback interface for progress reporting during extraction.
     */
    public interface ProgressCallback {
        /**
         * Called to report progress.
         *
         * @param current Current item index
         * @param total Total items to process
         * @param itemId Current item ID being processed
         */
        void onProgress(int current, int total, String itemId);
    }

    /**
     * Extract item and fluid metadata from a list of recipes.
     * Collects all unique item/fluid IDs from recipe inputs/outputs and extracts their metadata.
     *
     * @param recipes Recipe data to extract items from
     * @param callback Progress callback (may be null)
     * @return Map of item/fluid ID to ItemMetadata (fluids use their fluidId as the key)
     */
    public static Map<String, ItemMetadata> extractFromRecipes(List<RecipeData> recipes,
                                                                ProgressCallback callback) {
        // Step 1: Collect unique item IDs from all recipes
        Set<String> itemIds = new HashSet<>();
        Set<String> fluidIds = new HashSet<>();
        for (RecipeData recipe : recipes) {
            collectItemIds(recipe, itemIds);
            collectFluidIds(recipe, fluidIds);
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Collected {} unique items and {} unique fluids from {} recipes",
                    itemIds.size(), fluidIds.size(), recipes.size());
        }

        // Step 2: Extract metadata for each item
        Map<String, ItemMetadata> metadata = new LinkedHashMap<>();
        int current = 0;
        int total = itemIds.size() + fluidIds.size();

        for (String itemId : itemIds) {
            current++;

            if (callback != null) {
                callback.onProgress(current, total, itemId);
            }

            try {
                ItemMetadata itemMeta = extractItemMetadata(itemId);
                if (itemMeta != null) {
                    metadata.put(itemId, itemMeta);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to extract metadata for item: {}", itemId, e);
            }
        }

        // Step 3: Extract metadata for each fluid
        for (String fluidId : fluidIds) {
            current++;

            if (callback != null) {
                callback.onProgress(current, total, fluidId);
            }

            try {
                ItemMetadata fluidMeta = extractFluidMetadata(fluidId);
                if (fluidMeta != null) {
                    metadata.put(fluidId, fluidMeta);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to extract metadata for fluid: {}", fluidId, e);
            }
        }

        // Clear the creative tab cache after extraction completes to free memory
        CreativeTabIndexer.clearCache();

        LOGGER.info("Extracted metadata for {} items and {} fluids", itemIds.size(), fluidIds.size());
        return metadata;
    }

    /**
     * Collect all unique item IDs from a recipe.
     */
    private static void collectItemIds(RecipeData recipe, Set<String> itemIds) {
        if (recipe instanceof VanillaRecipeData) {
            collectVanillaItemIds((VanillaRecipeData) recipe, itemIds);
        } else if (recipe instanceof GregTechRecipeData) {
            collectGregTechItemIds((GregTechRecipeData) recipe, itemIds);
        }
        // Add support for other recipe types here as needed
    }

    /**
     * Collect item IDs from vanilla recipes.
     */
    private static void collectVanillaItemIds(VanillaRecipeData recipe, Set<String> itemIds) {
        // Output
        if (recipe.getOutput() != null) {
            itemIds.add(recipe.getOutput().getItemId());
        }

        // Shaped crafting key
        if (recipe.getKey() != null) {
            for (ItemStackData item : recipe.getKey().values()) {
                itemIds.add(item.getItemId());
            }
        }

        // Shapeless ingredients
        if (recipe.getIngredients() != null) {
            for (ItemStackData item : recipe.getIngredients()) {
                itemIds.add(item.getItemId());
            }
        }

        // Smelting/cooking input
        if (recipe.getInput() != null) {
            itemIds.add(recipe.getInput().getItemId());
        }

        // Smithing recipe components
        if (recipe.getTemplate() != null) {
            itemIds.add(recipe.getTemplate().getItemId());
        }
        if (recipe.getBase() != null) {
            itemIds.add(recipe.getBase().getItemId());
        }
        if (recipe.getAddition() != null) {
            itemIds.add(recipe.getAddition().getItemId());
        }
    }

    /**
     * Collect item IDs from GregTech recipes.
     */
    private static void collectGregTechItemIds(GregTechRecipeData recipe, Set<String> itemIds) {
        // Input items
        for (ItemStackData item : recipe.getInputItems()) {
            itemIds.add(item.getItemId());
        }

        // Output items
        for (GregTechRecipeData.ChancedItemOutput item : recipe.getOutputItems()) {
            itemIds.add(item.getItemId());
        }

        // Note: Fluids are collected separately via collectFluidIds
    }

    /**
     * Collect all unique fluid IDs from a recipe.
     */
    private static void collectFluidIds(RecipeData recipe, Set<String> fluidIds) {
        if (recipe instanceof GregTechRecipeData gtRecipe) {
            // Input fluids
            for (FluidStackData fluid : gtRecipe.getInputFluids()) {
                fluidIds.add(fluid.getFluidId());
            }

            // Output fluids
            for (FluidStackData fluid : gtRecipe.getOutputFluids()) {
                fluidIds.add(fluid.getFluidId());
            }
        }
        // Add support for other recipe types with fluids here as needed
    }

    /**
     * Extract metadata for a single item.
     *
     * @param itemId Item registry name (e.g., "minecraft:iron_ingot")
     * @return ItemMetadata or null if item not found
     */
    private static ItemMetadata extractItemMetadata(String itemId) {
        // Parse item ID as ResourceLocation
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        if (location == null) {
            LOGGER.warn("Invalid item ID: {}", itemId);
            return null;
        }

        // Get item from registry
        Item item = ForgeRegistries.ITEMS.getValue(location);
        if (item == null) {
            LOGGER.warn("Item not found in registry: {}", itemId);
            return null;
        }

        // Create item stack
        ItemStack stack = new ItemStack(item);
        if (stack.isEmpty()) {
            LOGGER.warn("Failed to create item stack for: {}", itemId);
            return null;
        }

        // Get display name
        String displayName = stack.getHoverName().getString();

        // Get tooltips (normal and shift-extended)
        List<String> tooltipLines = new ArrayList<>();
        List<String> shiftTooltipLines = null;
        try {
            Minecraft mc = Minecraft.getInstance();

            // Get normal tooltips (without shift held)
            List<Component> normalComponents = stack.getTooltipLines(
                    mc.player,
                    TooltipFlag.Default.NORMAL
            );
            // Skip first line (it's the display name)
            for (int i = 1; i < normalComponents.size(); i++) {
                String line = normalComponents.get(i).getString();
                if (line != null && !line.trim().isEmpty()) {
                    tooltipLines.add(line);
                }
            }

            // Get shift tooltips by simulating shift key held
            // This uses a mixin to make Screen.hasShiftDown() return true
            List<String> shiftLines = new ArrayList<>();
            try {
                ShiftKeyHelper.FORCE_SHIFT_DOWN.set(true);
                List<Component> shiftComponents = stack.getTooltipLines(
                        mc.player,
                        TooltipFlag.Default.NORMAL
                );
                for (int i = 1; i < shiftComponents.size(); i++) {
                    String line = shiftComponents.get(i).getString();
                    if (line != null && !line.trim().isEmpty()) {
                        shiftLines.add(line);
                    }
                }
            } finally {
                ShiftKeyHelper.FORCE_SHIFT_DOWN.set(false);
            }

            // Only include shiftTooltipLines if they differ from normal
            if (!shiftLines.equals(tooltipLines)) {
                shiftTooltipLines = shiftLines;
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to extract tooltips for {}: {}", itemId, e.getMessage());
            // Continue with just the display name
        }

        // Get creative tab placement
        String creativeTab = null;
        Integer sortOrder = null;
        CreativeTabIndexer.TabPlacement placement = CreativeTabIndexer.getTabPlacement(item);
        if (placement != null) {
            creativeTab = placement.getTabId();
            sortOrder = placement.getSortOrder();
        }

        return new ItemMetadata(itemId, displayName, tooltipLines, shiftTooltipLines, creativeTab, sortOrder);
    }

    /**
     * Extract metadata for a single fluid.
     *
     * @param fluidId Fluid registry name (e.g., "minecraft:water")
     * @return ItemMetadata or null if fluid not found
     */
    private static ItemMetadata extractFluidMetadata(String fluidId) {
        // Parse fluid ID as ResourceLocation
        ResourceLocation location = ResourceLocation.tryParse(fluidId);
        if (location == null) {
            LOGGER.warn("Invalid fluid ID: {}", fluidId);
            return null;
        }

        // Get fluid from registry
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(location);
        if (fluid == null) {
            LOGGER.warn("Fluid not found in registry: {}", fluidId);
            return null;
        }

        // Create fluid stack to get display name
        FluidStack fluidStack = new FluidStack(fluid, 1000); // 1 bucket
        if (fluidStack.isEmpty()) {
            LOGGER.warn("Failed to create fluid stack for: {}", fluidId);
            return null;
        }

        // Get display name
        String displayName = fluidStack.getDisplayName().getString();

        // Fluids typically don't have complex tooltips like items,
        // but we can add basic info
        List<String> tooltipLines = new ArrayList<>();

        // No creative tab for fluids (they're not items)
        return new ItemMetadata(fluidId, displayName, tooltipLines, null, null, null);
    }
}

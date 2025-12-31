package com.recipeflow.mod.v120plus.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.CreativeModeTabRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes items by their creative tab placement.
 * Builds a reverse mapping from Item -> (tabId, sortOrder) since items
 * don't directly store their tab in 1.20.1+.
 */
@OnlyIn(Dist.CLIENT)
public class CreativeTabIndexer {

    private static final Logger LOGGER = LogManager.getLogger();

    /**
     * Information about an item's placement in a creative tab.
     */
    public static class TabPlacement {
        private final String tabId;
        private final int sortOrder;

        public TabPlacement(String tabId, int sortOrder) {
            this.tabId = tabId;
            this.sortOrder = sortOrder;
        }

        public String getTabId() {
            return tabId;
        }

        public int getSortOrder() {
            return sortOrder;
        }
    }

    // Cached index, built lazily
    private static Map<Item, TabPlacement> cachedIndex = null;

    /**
     * Get the creative tab placement for an item.
     * Returns null if the item is not in any creative tab.
     *
     * @param item The item to look up
     * @return TabPlacement containing tab ID and sort order, or null if not in any tab
     */
    public static TabPlacement getTabPlacement(Item item) {
        if (cachedIndex == null) {
            cachedIndex = buildIndex();
        }
        return cachedIndex.get(item);
    }

    /**
     * Clear the cached index. Call this after extraction completes to free memory.
     */
    public static void clearCache() {
        cachedIndex = null;
    }

    /**
     * Build the complete item -> tab mapping by iterating all creative tabs.
     */
    private static Map<Item, TabPlacement> buildIndex() {
        Map<Item, TabPlacement> index = new HashMap<>();
        long startTime = System.currentTimeMillis();

        // Iterate through all registered creative tabs
        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            // Get the tab's registry name
            ResourceLocation tabName = CreativeModeTabRegistry.getName(tab);
            if (tabName == null) {
                continue; // Skip tabs without registry names
            }

            String tabId = tabName.toString();

            try {
                // Get items in this tab
                Collection<ItemStack> displayItems = tab.getDisplayItems();
                int sortOrder = 0;

                for (ItemStack stack : displayItems) {
                    if (stack.isEmpty()) {
                        continue;
                    }

                    Item item = stack.getItem();

                    // Only record first occurrence (first tab wins, matching JEI behavior)
                    if (!index.containsKey(item)) {
                        index.put(item, new TabPlacement(tabId, sortOrder));
                    }

                    sortOrder++;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to index creative tab {}: {}", tabId, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        LOGGER.info("Built creative tab index: {} items indexed in {}ms", index.size(), elapsed);

        return index;
    }
}

package com.recipeflow.mod.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Item metadata containing display name and tooltip information.
 * Used for enriching recipe data with user-facing item information.
 */
public class ItemMetadata {

    private final String itemId;
    private final String displayName;
    private final List<String> tooltipLines;
    private final List<String> shiftTooltipLines;
    private final String creativeTab;
    private final Integer sortOrder;

    /**
     * Create item metadata with creative tab information and shift tooltips.
     *
     * @param itemId Registry name (e.g., "minecraft:iron_ingot")
     * @param displayName Localized display name (e.g., "Iron Ingot")
     * @param tooltipLines Tooltip lines (excluding first line which is the display name)
     * @param shiftTooltipLines Extended tooltip lines shown when Shift is held, or null if same as tooltipLines
     * @param creativeTab Creative tab registry name (e.g., "minecraft:ingredients"), or null if not in any tab
     * @param sortOrder Position within the creative tab, or null if not in any tab
     */
    public ItemMetadata(String itemId, String displayName, List<String> tooltipLines,
                        List<String> shiftTooltipLines, String creativeTab, Integer sortOrder) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.tooltipLines = tooltipLines != null ? new ArrayList<>(tooltipLines) : new ArrayList<>();
        this.shiftTooltipLines = shiftTooltipLines != null ? new ArrayList<>(shiftTooltipLines) : null;
        this.creativeTab = creativeTab;
        this.sortOrder = sortOrder;
    }

    /**
     * Create item metadata without creative tab information.
     * Provided for backward compatibility.
     *
     * @param itemId Registry name (e.g., "minecraft:iron_ingot")
     * @param displayName Localized display name (e.g., "Iron Ingot")
     * @param tooltipLines Tooltip lines (excluding first line which is the display name)
     */
    public ItemMetadata(String itemId, String displayName, List<String> tooltipLines) {
        this(itemId, displayName, tooltipLines, null, null, null);
    }

    /**
     * Get the item registry ID.
     *
     * @return Item registry ID (e.g., "minecraft:iron_ingot")
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Get the localized display name.
     *
     * @return Display name (e.g., "Iron Ingot")
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Get tooltip lines (excluding the first line which is the display name).
     *
     * @return List of tooltip text lines
     */
    public List<String> getTooltipLines() {
        return new ArrayList<>(tooltipLines);
    }

    /**
     * Check if this item has any tooltip lines beyond the display name.
     *
     * @return true if tooltip lines exist
     */
    public boolean hasTooltips() {
        return !tooltipLines.isEmpty();
    }

    /**
     * Get extended tooltip lines shown when Shift is held.
     * Returns null if shift tooltips are identical to normal tooltips.
     *
     * @return List of extended tooltip lines, or null if same as normal tooltips
     */
    public List<String> getShiftTooltipLines() {
        return shiftTooltipLines != null ? new ArrayList<>(shiftTooltipLines) : null;
    }

    /**
     * Check if this item has different tooltips when Shift is held.
     *
     * @return true if shift tooltips differ from normal tooltips
     */
    public boolean hasShiftTooltips() {
        return shiftTooltipLines != null;
    }

    /**
     * Get the creative tab registry name.
     *
     * @return Creative tab ID (e.g., "minecraft:ingredients"), or null if not in any tab
     */
    public String getCreativeTab() {
        return creativeTab;
    }

    /**
     * Get the sort order within the creative tab.
     *
     * @return Position within the tab, or null if not in any tab
     */
    public Integer getSortOrder() {
        return sortOrder;
    }

    /**
     * Check if this item has creative tab placement information.
     *
     * @return true if item is in a creative tab
     */
    public boolean hasCreativeTab() {
        return creativeTab != null;
    }

    /**
     * Convert to JSON-serializable map for upload.
     *
     * @return Map suitable for JSON serialization
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", itemId);
        map.put("displayName", displayName);
        if (hasTooltips()) {
            map.put("tooltipLines", new ArrayList<>(tooltipLines));
        }
        if (hasShiftTooltips()) {
            map.put("shiftTooltipLines", new ArrayList<>(shiftTooltipLines));
        }
        if (creativeTab != null) {
            map.put("creativeTab", creativeTab);
        }
        if (sortOrder != null) {
            map.put("sortOrder", sortOrder);
        }
        return map;
    }

    @Override
    public String toString() {
        return "ItemMetadata{" +
                "itemId='" + itemId + '\'' +
                ", displayName='" + displayName + '\'' +
                ", tooltips=" + tooltipLines.size() +
                ", shiftTooltips=" + (shiftTooltipLines != null ? shiftTooltipLines.size() : "same") +
                ", creativeTab='" + creativeTab + '\'' +
                ", sortOrder=" + sortOrder +
                '}';
    }
}

package com.recipeflow.mod.core.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable representation of an item stack.
 * Matches TypeScript ItemStack interface.
 */
public class ItemStackData {

    private final String itemId;
    private final int count;
    private final Map<String, Object> nbt;
    private final Boolean consumed; // null = true (default), false = not consumed (molds, lenses, etc.)

    public ItemStackData(String itemId, int count) {
        this(itemId, count, null, null);
    }

    public ItemStackData(String itemId, int count, Map<String, Object> nbt) {
        this(itemId, count, nbt, null);
    }

    public ItemStackData(String itemId, int count, Map<String, Object> nbt, Boolean consumed) {
        this.itemId = itemId;
        this.count = count;
        this.nbt = nbt;
        this.consumed = consumed;
    }

    /**
     * Create a non-consumed item stack (for molds, lenses, presses, etc.)
     */
    public static ItemStackData notConsumed(String itemId, int count) {
        return new ItemStackData(itemId, count, null, false);
    }

    /**
     * Create a non-consumed item stack with NBT data.
     */
    public static ItemStackData notConsumed(String itemId, int count, Map<String, Object> nbt) {
        return new ItemStackData(itemId, count, nbt, false);
    }

    public String getItemId() {
        return itemId;
    }

    public int getCount() {
        return count;
    }

    public Map<String, Object> getNbt() {
        return nbt;
    }

    public boolean hasNbt() {
        return nbt != null && !nbt.isEmpty();
    }

    /**
     * Check if this item is consumed during crafting.
     * Returns true if consumed (default), false if not consumed (molds, lenses, etc.)
     */
    public boolean isConsumed() {
        return consumed == null || consumed;
    }

    /**
     * Get the raw consumed flag (may be null).
     */
    public Boolean getConsumed() {
        return consumed;
    }

    /**
     * Convert to a JSON-serializable map.
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", itemId);
        map.put("count", count);
        if (hasNbt()) {
            map.put("nbt", nbt);
        }
        if (consumed != null && !consumed) {
            map.put("consumed", false);
        }
        return map;
    }

    @Override
    public String toString() {
        return "ItemStackData{" +
                "itemId='" + itemId + '\'' +
                ", count=" + count +
                (hasNbt() ? ", nbt=" + nbt : "") +
                (!isConsumed() ? ", consumed=false" : "") +
                '}';
    }
}

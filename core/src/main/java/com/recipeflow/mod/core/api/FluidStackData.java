package com.recipeflow.mod.core.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable representation of a fluid stack.
 * Matches TypeScript FluidStack interface.
 */
public class FluidStackData {

    private final String fluidId;
    private final int amount; // millibuckets (mB)

    public FluidStackData(String fluidId, int amount) {
        this.fluidId = fluidId;
        this.amount = amount;
    }

    public String getFluidId() {
        return fluidId;
    }

    public int getAmount() {
        return amount;
    }

    /**
     * Convert to a JSON-serializable map.
     */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fluidId", fluidId);
        map.put("amount", amount);
        return map;
    }

    @Override
    public String toString() {
        return "FluidStackData{" +
                "fluidId='" + fluidId + '\'' +
                ", amount=" + amount +
                '}';
    }
}

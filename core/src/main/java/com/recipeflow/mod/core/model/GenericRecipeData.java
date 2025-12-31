package com.recipeflow.mod.core.model;

import com.recipeflow.mod.core.api.FluidStackData;
import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic recipe data for mods without explicit support.
 * Matches TypeScript GenericMachineRecipe interface.
 * Flexible structure to capture arbitrary recipe data from EMI/JEI.
 */
public class GenericRecipeData extends RecipeData {

    private String machineType;
    private Integer energy;
    private Integer duration;

    private List<ItemStackData> inputItems = new ArrayList<>();
    private List<FluidStackData> inputFluids = new ArrayList<>();
    private Map<String, Object> extraInputs = new LinkedHashMap<>();

    private List<ItemStackData> outputItems = new ArrayList<>();
    private List<FluidStackData> outputFluids = new ArrayList<>();
    private Map<String, Object> extraOutputs = new LinkedHashMap<>();

    private Map<String, Object> conditions = new LinkedHashMap<>();

    public GenericRecipeData() {
        this.sourceMod = "unknown";
    }

    public GenericRecipeData(String type, String sourceMod) {
        this.type = type;
        this.sourceMod = sourceMod;
    }

    public String getMachineType() {
        return machineType;
    }

    public void setMachineType(String machineType) {
        this.machineType = machineType;
    }

    public Integer getEnergy() {
        return energy;
    }

    public void setEnergy(Integer energy) {
        this.energy = energy;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }

    public List<ItemStackData> getInputItems() {
        return inputItems;
    }

    public void setInputItems(List<ItemStackData> inputItems) {
        this.inputItems = inputItems;
    }

    public void addInputItem(ItemStackData item) {
        this.inputItems.add(item);
    }

    public List<FluidStackData> getInputFluids() {
        return inputFluids;
    }

    public void setInputFluids(List<FluidStackData> inputFluids) {
        this.inputFluids = inputFluids;
    }

    public void addInputFluid(FluidStackData fluid) {
        this.inputFluids.add(fluid);
    }

    public Map<String, Object> getExtraInputs() {
        return extraInputs;
    }

    public void setExtraInputs(Map<String, Object> extraInputs) {
        this.extraInputs = extraInputs;
    }

    public void addExtraInput(String key, Object value) {
        this.extraInputs.put(key, value);
    }

    public List<ItemStackData> getOutputItems() {
        return outputItems;
    }

    public void setOutputItems(List<ItemStackData> outputItems) {
        this.outputItems = outputItems;
    }

    public void addOutputItem(ItemStackData item) {
        this.outputItems.add(item);
    }

    public List<FluidStackData> getOutputFluids() {
        return outputFluids;
    }

    public void setOutputFluids(List<FluidStackData> outputFluids) {
        this.outputFluids = outputFluids;
    }

    public void addOutputFluid(FluidStackData fluid) {
        this.outputFluids.add(fluid);
    }

    public Map<String, Object> getExtraOutputs() {
        return extraOutputs;
    }

    public void setExtraOutputs(Map<String, Object> extraOutputs) {
        this.extraOutputs = extraOutputs;
    }

    public void addExtraOutput(String key, Object value) {
        this.extraOutputs.put(key, value);
    }

    public Map<String, Object> getConditions() {
        return conditions;
    }

    public void setConditions(Map<String, Object> conditions) {
        this.conditions = conditions;
    }

    public void addCondition(String key, Object value) {
        this.conditions.put(key, value);
    }

    @Override
    public String getOutputItemId() {
        if (!outputItems.isEmpty()) {
            return outputItems.get(0).getItemId();
        }
        return null;
    }

    @Override
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);

        if (machineType != null) {
            map.put("machineType", machineType);
        }
        if (energy != null) {
            map.put("energy", energy);
        }
        if (duration != null) {
            map.put("duration", duration);
        }

        // Inputs
        Map<String, Object> inputs = new LinkedHashMap<>();

        List<Map<String, Object>> itemInputMaps = new ArrayList<>();
        for (ItemStackData item : inputItems) {
            itemInputMaps.add(item.toJsonMap());
        }
        inputs.put("items", itemInputMaps);

        if (!inputFluids.isEmpty()) {
            List<Map<String, Object>> fluidInputMaps = new ArrayList<>();
            for (FluidStackData fluid : inputFluids) {
                fluidInputMaps.add(fluid.toJsonMap());
            }
            inputs.put("fluids", fluidInputMaps);
        }

        // Add any extra inputs
        inputs.putAll(extraInputs);
        map.put("inputs", inputs);

        // Outputs
        Map<String, Object> outputs = new LinkedHashMap<>();

        List<Map<String, Object>> itemOutputMaps = new ArrayList<>();
        for (ItemStackData item : outputItems) {
            itemOutputMaps.add(item.toJsonMap());
        }
        outputs.put("items", itemOutputMaps);

        if (!outputFluids.isEmpty()) {
            List<Map<String, Object>> fluidOutputMaps = new ArrayList<>();
            for (FluidStackData fluid : outputFluids) {
                fluidOutputMaps.add(fluid.toJsonMap());
            }
            outputs.put("fluids", fluidOutputMaps);
        }

        // Add any extra outputs
        outputs.putAll(extraOutputs);
        map.put("outputs", outputs);

        // Conditions
        if (!conditions.isEmpty()) {
            map.put("conditions", conditions);
        }

        return map;
    }
}

package com.recipeflow.mod.core.model;

import com.recipeflow.mod.core.api.FluidStackData;
import com.recipeflow.mod.core.api.ItemStackData;
import com.recipeflow.mod.core.api.RecipeData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe data for GregTech machine recipes.
 * Matches TypeScript GregTechMachineRecipe interface.
 */
public class GregTechRecipeData extends RecipeData {

    /**
     * GregTech voltage tiers with their EU/t values.
     */
    public enum VoltageTier {
        ULV(8),
        LV(32),
        MV(128),
        HV(512),
        EV(2048),
        IV(8192),
        LuV(32768),
        ZPM(131072),
        UV(524288),
        UHV(2097152),
        UEV(8388608),
        UIV(33554432),
        UXV(134217728),
        OpV(536870912),
        MAX(Integer.MAX_VALUE);

        private final int voltage;

        VoltageTier(int voltage) {
            this.voltage = voltage;
        }

        public int getVoltage() {
            return voltage;
        }

        /**
         * Determine voltage tier from EU/t value.
         */
        public static VoltageTier fromEUt(long euPerTick) {
            // Use absolute value for generators (negative EU/t)
            long absEu = Math.abs(euPerTick);
            for (VoltageTier tier : values()) {
                if (absEu <= tier.voltage) {
                    return tier;
                }
            }
            return MAX;
        }
    }

    /**
     * Item output with optional chance.
     */
    public static class ChancedItemOutput extends ItemStackData {
        private final Double chance;       // 0-1, null = 100%
        private final Double boostPerTier; // Chance increase per voltage tier

        public ChancedItemOutput(String itemId, int count, Double chance, Double boostPerTier) {
            super(itemId, count);
            this.chance = chance;
            this.boostPerTier = boostPerTier;
        }

        public ChancedItemOutput(String itemId, int count, Map<String, Object> nbt, Double chance, Double boostPerTier) {
            super(itemId, count, nbt);
            this.chance = chance;
            this.boostPerTier = boostPerTier;
        }

        public Double getChance() {
            return chance;
        }

        public Double getBoostPerTier() {
            return boostPerTier;
        }

        public boolean isChanced() {
            return chance != null && chance < 1.0;
        }

        @Override
        public Map<String, Object> toJsonMap() {
            Map<String, Object> map = super.toJsonMap();
            if (chance != null) {
                map.put("chance", chance);
            }
            if (boostPerTier != null) {
                map.put("boostPerTier", boostPerTier);
            }
            return map;
        }
    }

    /**
     * Special conditions for recipe execution.
     */
    public static class SpecialConditions {
        private Boolean cleanroom;
        private Boolean vacuum;
        private Integer coilTier;
        private Map<String, Object> extra;

        public Boolean getCleanroom() {
            return cleanroom;
        }

        public void setCleanroom(Boolean cleanroom) {
            this.cleanroom = cleanroom;
        }

        public Boolean getVacuum() {
            return vacuum;
        }

        public void setVacuum(Boolean vacuum) {
            this.vacuum = vacuum;
        }

        public Integer getCoilTier() {
            return coilTier;
        }

        public void setCoilTier(Integer coilTier) {
            this.coilTier = coilTier;
        }

        public Map<String, Object> getExtra() {
            return extra;
        }

        public void setExtra(Map<String, Object> extra) {
            this.extra = extra;
        }

        public void addExtra(String key, Object value) {
            if (extra == null) {
                extra = new LinkedHashMap<>();
            }
            extra.put(key, value);
        }

        public boolean isEmpty() {
            return (cleanroom == null || !cleanroom)
                    && (vacuum == null || !vacuum)
                    && coilTier == null
                    && (extra == null || extra.isEmpty());
        }

        public Map<String, Object> toJsonMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            if (cleanroom != null && cleanroom) {
                map.put("cleanroom", true);
            }
            if (vacuum != null && vacuum) {
                map.put("vacuum", true);
            }
            if (coilTier != null) {
                map.put("coilTier", coilTier);
            }
            if (extra != null && !extra.isEmpty()) {
                map.putAll(extra);
            }
            return map;
        }
    }

    private String machineType;
    private VoltageTier voltageTier;
    private int euPerTick;
    private int duration; // ticks

    private List<ItemStackData> inputItems = new ArrayList<>();
    private List<FluidStackData> inputFluids = new ArrayList<>();
    private List<ChancedItemOutput> outputItems = new ArrayList<>();
    private List<FluidStackData> outputFluids = new ArrayList<>();

    private SpecialConditions specialConditions;
    private Integer circuit; // 1-32, null if no circuit required

    public GregTechRecipeData() {
        this.type = "gregtech:machine";
        this.sourceMod = "gtceu";
    }

    public String getMachineType() {
        return machineType;
    }

    public void setMachineType(String machineType) {
        this.machineType = machineType;
    }

    public VoltageTier getVoltageTier() {
        return voltageTier;
    }

    public void setVoltageTier(VoltageTier voltageTier) {
        this.voltageTier = voltageTier;
    }

    public int getEuPerTick() {
        return euPerTick;
    }

    public void setEuPerTick(int euPerTick) {
        this.euPerTick = euPerTick;
        this.voltageTier = VoltageTier.fromEUt(euPerTick);
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
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

    public List<ChancedItemOutput> getOutputItems() {
        return outputItems;
    }

    public void setOutputItems(List<ChancedItemOutput> outputItems) {
        this.outputItems = outputItems;
    }

    public void addOutputItem(ChancedItemOutput item) {
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

    public SpecialConditions getSpecialConditions() {
        return specialConditions;
    }

    public void setSpecialConditions(SpecialConditions specialConditions) {
        this.specialConditions = specialConditions;
    }

    public SpecialConditions getOrCreateSpecialConditions() {
        if (specialConditions == null) {
            specialConditions = new SpecialConditions();
        }
        return specialConditions;
    }

    public Integer getCircuit() {
        return circuit;
    }

    public void setCircuit(Integer circuit) {
        this.circuit = circuit;
    }

    /**
     * Check if this recipe has any outputs (items or fluids).
     */
    public boolean hasOutputs() {
        return !outputItems.isEmpty() || !outputFluids.isEmpty();
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
        map.put("machineType", machineType);
        map.put("voltageTier", voltageTier != null ? voltageTier.name() : null);
        map.put("euPerTick", euPerTick);
        map.put("duration", duration);

        if (circuit != null) {
            map.put("circuit", circuit);
        }

        // Inputs
        Map<String, Object> inputs = new LinkedHashMap<>();
        List<Map<String, Object>> itemInputMaps = new ArrayList<>();
        for (ItemStackData item : inputItems) {
            itemInputMaps.add(item.toJsonMap());
        }
        inputs.put("items", itemInputMaps);

        List<Map<String, Object>> fluidInputMaps = new ArrayList<>();
        for (FluidStackData fluid : inputFluids) {
            fluidInputMaps.add(fluid.toJsonMap());
        }
        inputs.put("fluids", fluidInputMaps);
        map.put("inputs", inputs);

        // Outputs
        Map<String, Object> outputs = new LinkedHashMap<>();
        List<Map<String, Object>> itemOutputMaps = new ArrayList<>();
        for (ChancedItemOutput item : outputItems) {
            itemOutputMaps.add(item.toJsonMap());
        }
        outputs.put("items", itemOutputMaps);

        List<Map<String, Object>> fluidOutputMaps = new ArrayList<>();
        for (FluidStackData fluid : outputFluids) {
            fluidOutputMaps.add(fluid.toJsonMap());
        }
        outputs.put("fluids", fluidOutputMaps);
        map.put("outputs", outputs);

        // Special conditions
        if (specialConditions != null && !specialConditions.isEmpty()) {
            map.put("specialConditions", specialConditions.toJsonMap());
        }

        return map;
    }
}

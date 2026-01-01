# Task 02: Thermal Series Recipe Provider

## Priority: High (Tier 1)

## Overview
Implement a custom `ThermalRecipeProvider` to extract Thermal Foundation/Expansion/Cultivation recipes with RF energy costs, catalyst items, and machine-specific metadata.

## Mod Details
- **Mods**: Thermal Foundation, Thermal Expansion, Thermal Cultivation + Addons
- **Files**:
  - `thermal_foundation-1.20.1-11.0.6.70.jar` (base)
  - `thermal_expansion-1.20.1-11.0.1.29.jar` (base)
  - `thermal_cultivation-1.20.1-11.0.1.24.jar` (base)
  - `ThermalExtra-3.3.0-1.20.1.jar`
  - `systeams-1.20.1-1.9.2.jar`
- **Namespaces**: `thermal`, `thermal_extra`, `systeams`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `energyCost` | int | Total RF required for the recipe |
| `energyRate` | int | RF/t consumption rate |
| `processingTime` | int | Duration in ticks |
| `catalyst` | ItemStackData | Catalyst item (if applicable) |
| `catalystChance` | float | Chance catalyst is consumed |
| `experience` | float | XP granted on completion |
| `tankCapacity` | int | Fluid capacity requirements |
| `addon` | string | Addon mod that added this recipe (null if base Thermal) |
| `steamCost` | int | Steam consumed in mB (Systeams) |
| `steamPowered` | boolean | Whether machine uses steam instead of RF (Systeams) |

## Recipe Types to Handle

### Thermal Expansion Machines
- `thermal:smelter` - Induction Smelter (alloy recipes)
- `thermal:sawmill` - Sawmill
- `thermal:pulverizer` - Pulverizer (ore processing)
- `thermal:insolator` - Phytogenic Insolator (farming)
- `thermal:centrifuge` - Centrifugal Separator
- `thermal:press` - Multiservo Press
- `thermal:crucible` - Magma Crucible
- `thermal:chiller` - Blast Chiller
- `thermal:refinery` - Fractionating Still
- `thermal:bottler` - Fluid Encapsulator
- `thermal:brewer` - Alchemical Imbuer

### Dynamo Fuels
- `thermal:stirling_fuel` - Stirling Dynamo fuels
- `thermal:compression_fuel` - Compression Dynamo fuels
- `thermal:magmatic_fuel` - Magmatic Dynamo fuels
- `thermal:numismatic_fuel` - Numismatic Dynamo fuels
- `thermal:lapidary_fuel` - Lapidary Dynamo fuels

### Thermal Cultivation
- `thermal:hive_extractor` - Hive processing

### Addon Recipe Types
- Thermal Extra: Additional machine recipes (same types as base)
- Systeams: Steam-powered variants of all Thermal machines

## Implementation Notes

1. **API Access**: Thermal uses `cofh.thermal.core.recipe` package
2. **Energy Values**: Stored in recipe as `energy` field
3. **Catalysts**: Machine augments can modify catalyst behavior
4. **Secondary Outputs**: Many recipes have chanced secondary outputs
5. **Addon Detection**: Set `addon` field based on recipe namespace
6. **Systeams**: For steam-powered machines, capture `steamCost` instead of/alongside `energyCost`

## Example Output JSON

```json
{
  "id": "thermal:smelter/bronze_ingot",
  "type": "thermal:smelter",
  "sourceMod": "thermal",
  "machineType": "induction_smelter",
  "energyCost": 4000,
  "processingTime": 40,
  "inputs": {
    "items": [
      { "itemId": "minecraft:copper_ingot", "count": 3 },
      { "itemId": "thermal:tin_ingot", "count": 1 }
    ]
  },
  "outputs": {
    "items": [
      { "itemId": "thermal:bronze_ingot", "count": 4 }
    ]
  }
}
```

```json
{
  "id": "thermal:pulverizer/iron_ore",
  "type": "thermal:pulverizer",
  "sourceMod": "thermal",
  "machineType": "pulverizer",
  "energyCost": 3000,
  "processingTime": 30,
  "inputs": {
    "items": [
      { "itemId": "minecraft:iron_ore", "count": 1 }
    ]
  },
  "outputs": {
    "items": [
      { "itemId": "thermal:iron_dust", "count": 2 },
      { "itemId": "thermal:nickel_dust", "count": 1, "chance": 0.15 }
    ]
  },
  "experience": 0.2
}
```

## Acceptance Criteria

- [ ] All Thermal machine recipe types are extracted
- [ ] RF energy costs are captured accurately
- [ ] Catalyst items and consumption chances are included
- [ ] Secondary/chanced outputs are properly represented
- [ ] Dynamo fuel values are captured
- [ ] Addon recipes are captured with `addon` field set appropriately
- [ ] Systeams steam costs are captured for steam-powered variants
- [ ] Provider integrates with ProviderRegistry (priority ~75)

## References

- GTCEu provider: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- Thermal GitHub: https://github.com/CoFH/ThermalExpansion

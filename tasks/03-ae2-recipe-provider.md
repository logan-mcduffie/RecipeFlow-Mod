# Task 03: Applied Energistics 2 Recipe Provider

## Priority: High (Tier 1)

## Overview
Implement a custom `AE2RecipeProvider` to extract Applied Energistics 2 recipes, particularly the Inscriber with its unique slot-based crafting system.

## Mod Details
- **Mod**: Applied Energistics 2 + Addons
- **Files**:
  - `appliedenergistics2-forge-15.4.10.jar` (base)
  - `ae2wtlib-15.3.1-forge.jar`
  - `expandedae-1.2.7.jar`
  - `ExtendedAE-1.20-1.4.8-forge.jar`
  - `megacells-forge-2.4.6-1.20.1.jar`
  - `ae2ct-1.20.1-1.1.1.jar`
  - `betterp2p-1.5.0-forge.jar`
  - `merequester-forge-1.20.1-1.1.5.jar`
- **Namespaces**: `ae2`, `ae2wtlib`, `expandedae`, `extendedae`, `megacells`, `ae2ct`, `betterp2p`, `merequester`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `inscriber.top` | ItemStackData | Top slot input (usually a press) |
| `inscriber.middle` | ItemStackData | Middle slot input (main ingredient) |
| `inscriber.bottom` | ItemStackData | Bottom slot input (usually a press) |
| `pressType` | string | Type of press used (logic, calculation, engineering, silicon) |
| `consumesTop` | boolean | Whether top input is consumed |
| `consumesBottom` | boolean | Whether bottom input is consumed |
| `addon` | string | Addon mod that added this recipe (null if base AE2) |

## Recipe Types to Handle

### Inscriber Recipes
- `ae2:inscriber` - Main inscriber processing
  - Press recipes (logic, calculation, engineering, silicon, name)
  - Printed circuit recipes
  - Processor assembly recipes

### Other AE2 Recipe Types
- `ae2:grinder` - Quartz Grinder (manual processing)
- `ae2:charger` - Charger recipes
- `ae2:transform` - In-world transformation recipes
- `ae2:entropy` - Entropy Manipulator transformations

### Pattern-Based (may need special handling)
- Processing patterns (dynamic, player-created)
- Crafting patterns

## Example Output JSON

```json
{
  "id": "ae2:inscriber/printed_logic_processor",
  "type": "ae2:inscriber",
  "sourceMod": "ae2",
  "machineType": "inscriber",
  "slots": {
    "top": { "itemId": "ae2:logic_processor_press", "count": 1, "consumed": false },
    "middle": { "itemId": "minecraft:gold_ingot", "count": 1, "consumed": true },
    "bottom": null
  },
  "output": { "itemId": "ae2:printed_logic_processor", "count": 1 }
}
```

```json
{
  "id": "ae2:inscriber/logic_processor",
  "type": "ae2:inscriber",
  "sourceMod": "ae2",
  "machineType": "inscriber",
  "slots": {
    "top": { "itemId": "ae2:printed_logic_processor", "count": 1, "consumed": true },
    "middle": { "itemId": "minecraft:redstone", "count": 1, "consumed": true },
    "bottom": { "itemId": "ae2:printed_silicon", "count": 1, "consumed": true }
  },
  "output": { "itemId": "ae2:logic_processor", "count": 1 }
}
```

```json
{
  "id": "ae2:charger/charged_certus",
  "type": "ae2:charger",
  "sourceMod": "ae2",
  "machineType": "charger",
  "input": { "itemId": "ae2:certus_quartz_crystal", "count": 1 },
  "output": { "itemId": "ae2:charged_certus_quartz_crystal", "count": 1 },
  "energyCost": 1600
}
```

## Implementation Notes

1. **API Access**: AE2 uses `appeng.recipes.handlers` package
2. **Inscriber Slots**: The 3-slot system is unique to AE2
3. **Presses**: Are not consumed, but this should be explicitly captured
4. **Processors**: Multi-step crafting (printed circuits → processors)
5. **Addon Detection**: Set `addon` field based on recipe namespace
6. **Addon Handling**: Most addons use standard AE2 recipe types or vanilla crafting

## Acceptance Criteria

- [ ] Inscriber recipes capture all 3 slots correctly
- [ ] Press consumption (true/false) is tracked
- [ ] Charger recipes include energy costs
- [ ] Grinder recipes are extracted
- [ ] Transform/Entropy recipes are captured
- [ ] Addon recipes are captured with `addon` field set appropriately
- [ ] Provider integrates with ProviderRegistry (priority ~70)

## References

- GTCEu provider: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- AE2 GitHub: https://github.com/AppliedEnergistics/Applied-Energistics-2

# Task 01: Create Mod Recipe Provider

## Priority: High (Tier 1)

## Overview
Implement a custom `CreateRecipeProvider` to extract Create mod machine recipes with their unique metadata that the standard recipe extraction cannot capture.

## Mod Details
- **Mod**: Create + Addons
- **Files**:
  - `create-1.20.1-6.0.8.jar` (base)
  - `createdieselgenerators-1.20.1-1.3.5.jar`
  - `create-new-age-forge-1.20.1-1.1.4.jar`
  - `createlowheated-forge-1.20.1-6.0.6-4.jar`
  - `Steam_Rails-1.6.14-beta+forge-mc1.20.1.jar`
  - `copycats-3.0.2+mc.1.20.1-forge.jar`
  - `create_hypertube-0.2.6-hotfix-FORGE.jar`
  - `vintage-1.20.1-1.4.5.jar`
- **Namespaces**: `create`, `createdieselgenerators`, `create_new_age`, `createlowheated`, `railways`, `copycats`, `create_hypertube`, `vintage`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `stressImpact` | int | Stress units (SU) consumed by the machine |
| `requiredRPM` | int | Minimum RPM needed for the recipe |
| `processingTime` | int | Duration in ticks |
| `heatLevel` | enum | `none`, `low_heated`, `heated`, `superheated` |
| `sequenceSteps` | array | For sequenced assembly - ordered list of operations |
| `addon` | string | Addon mod that added this recipe (null if base Create) |
| `stressGenerated` | int | SU produced (for generators) |
| `energyConversion` | object | FE↔SU conversion data (New Age motors/generators) |
| `distillationOutputs` | array | Multi-height fluid outputs (Diesel Generators) |

## Recipe Types to Handle

### Processing Recipes
- `create:mixing` - Mechanical Mixer (basin)
- `create:crushing` - Crushing Wheels
- `create:milling` - Millstone
- `create:pressing` - Mechanical Press
- `create:cutting` - Mechanical Saw
- `create:deploying` - Deployer
- `create:filling` - Spout
- `create:emptying` - Item Drain
- `create:haunting` - Soul Fire processing
- `create:splashing` - Fan + Water
- `create:smoking` - Fan + Fire
- `create:blasting` - Fan + Lava
- `create:compacting` - Press + Basin
- `create:sandpaper_polishing` - Sandpaper

### Complex Recipes
- `create:sequenced_assembly` - Multi-step crafting sequences
- `create:mechanical_crafting` - Extended crafting grid

### Addon Recipe Types
- `createdieselgenerators:distillation` - Distillation tower (multi-height fluid outputs)
- `createdieselgenerators:fuel` - Generator fuel values
- `create_new_age:motor` - FE → SU conversion
- `create_new_age:generator` - SU → FE conversion
- Any additional processing types from addons using Create's recipe system

## Implementation Notes

1. **API Access**: Create uses `AllRecipeTypes` enum for recipe type registration
2. **Stress Values**: Available via `IRotationHandler` or block properties
3. **Heat Requirements**: Stored in recipe JSON as `heatRequirement` field (include `low_heated` from Low Heated addon)
4. **Sequenced Assembly**: Contains `loops`, `transitionalItem`, and `sequence` array
5. **Addon Detection**: Set `addon` field based on recipe namespace (e.g., `createdieselgenerators`, `create_new_age`)
6. **Namespace Handling**: Provider should handle all Create-family namespaces listed above

## Example Output JSON

```json
{
  "id": "create:mixing/brass_ingot",
  "type": "create:mixing",
  "sourceMod": "create",
  "machineType": "mechanical_mixer",
  "processingTime": 100,
  "heatLevel": "heated",
  "stressImpact": 4,
  "inputs": {
    "items": [
      { "itemId": "minecraft:copper_ingot", "count": 1 },
      { "itemId": "create:zinc_ingot", "count": 1 }
    ],
    "fluids": []
  },
  "outputs": {
    "items": [
      { "itemId": "create:brass_ingot", "count": 2 }
    ],
    "fluids": []
  }
}
```

```json
{
  "id": "create:sequenced_assembly/precision_mechanism",
  "type": "create:sequenced_assembly",
  "sourceMod": "create",
  "loops": 5,
  "transitionalItem": "create:incomplete_precision_mechanism",
  "input": { "itemId": "create:golden_sheet", "count": 1 },
  "sequence": [
    { "type": "deploying", "item": "create:cogwheel" },
    { "type": "deploying", "item": "create:large_cogwheel" },
    { "type": "deploying", "item": "create:iron_nugget" }
  ],
  "outputs": [
    { "itemId": "create:precision_mechanism", "count": 1, "chance": 1.0 },
    { "itemId": "create:golden_sheet", "count": 1, "chance": 0.5 }
  ]
}
```

## Acceptance Criteria

- [ ] All Create processing recipe types are extracted
- [ ] Stress impact values are captured correctly
- [ ] Heat requirements (none/low_heated/heated/superheated) are included
- [ ] Sequenced assembly recipes include full step sequences
- [ ] Mechanical crafting patterns are preserved
- [ ] Addon recipes are captured with `addon` field set appropriately
- [ ] Diesel Generators distillation recipes include height-based outputs
- [ ] New Age motor/generator energy conversions are captured
- [ ] Provider integrates with existing ProviderRegistry (priority ~80)

## References

- GTCEu provider implementation: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- Create GitHub: https://github.com/Creators-of-Create/Create

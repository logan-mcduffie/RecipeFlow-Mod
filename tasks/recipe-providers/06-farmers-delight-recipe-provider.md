# Task 06: Farmer's Delight Recipe Provider

## Priority: Medium (Tier 2)

## Overview
Implement a custom `FarmersDelightRecipeProvider` to extract Farmer's Delight cooking pot and cutting board recipes with their unique mechanics.

## Mod Details
- **Mod**: Farmer's Delight
- **File**: `FarmersDelight-1.20.1-1.2.9.jar`
- **Namespace**: `farmersdelight`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `cookingTime` | int | Duration in ticks for cooking pot |
| `experience` | float | XP granted on completion |
| `container` | ItemStackData | Container item returned (bowl, bottle, etc.) |
| `tool` | string | Tool type for cutting (knife, axe, pickaxe, shears) |
| `heatSource` | boolean | Whether heat source is required |
| `soundEvent` | string | Sound played during processing |

## Recipe Types to Handle

### Cooking Pot
- `farmersdelight:cooking` - Multi-ingredient cooking
  - Up to 6 ingredients
  - Container output
  - Heat source requirement
  - Cooking time

### Cutting Board
- `farmersdelight:cutting` - Tool-based processing
  - Tool type requirement
  - Multiple outputs
  - Sound effects

## Implementation Notes

1. **API Access**: Farmer's Delight uses `vectorwing.farmersdelight.common.crafting` package
2. **Cooking Pot**: Requires heat source (fire, campfire, magma, etc.)
3. **Cutting Board**: Different tools produce different results from same input
4. **Container Items**: Meals often return bowls/bottles that can be reused

## Example Output JSON

```json
{
  "id": "farmersdelight:cooking/beef_stew",
  "type": "farmersdelight:cooking",
  "sourceMod": "farmersdelight",
  "machineType": "cooking_pot",
  "cookingTime": 200,
  "experience": 1.0,
  "heatSourceRequired": true,
  "inputs": {
    "items": [
      { "itemId": "minecraft:beef", "count": 1 },
      { "itemId": "minecraft:carrot", "count": 1 },
      { "itemId": "minecraft:potato", "count": 1 },
      { "itemId": "minecraft:brown_mushroom", "count": 1 }
    ]
  },
  "output": { "itemId": "farmersdelight:beef_stew", "count": 1 },
  "container": { "itemId": "minecraft:bowl", "count": 1 }
}
```

```json
{
  "id": "farmersdelight:cutting/beef",
  "type": "farmersdelight:cutting",
  "sourceMod": "farmersdelight",
  "machineType": "cutting_board",
  "tool": "knife",
  "input": { "itemId": "minecraft:beef", "count": 1 },
  "outputs": [
    { "itemId": "farmersdelight:minced_beef", "count": 2 }
  ],
  "sound": "farmersdelight:block.cutting_board.knife"
}
```

```json
{
  "id": "farmersdelight:cutting/oak_log",
  "type": "farmersdelight:cutting",
  "sourceMod": "farmersdelight",
  "machineType": "cutting_board",
  "tool": "axe",
  "input": { "itemId": "minecraft:oak_log", "count": 1 },
  "outputs": [
    { "itemId": "minecraft:oak_planks", "count": 4 },
    { "itemId": "farmersdelight:tree_bark", "count": 1 }
  ],
  "sound": "farmersdelight:block.cutting_board.axe"
}
```

## Acceptance Criteria

- [ ] Cooking pot recipes capture all ingredients (up to 6)
- [ ] Cooking time and experience are included
- [ ] Container items are properly tracked
- [ ] Heat source requirement is captured
- [ ] Cutting board recipes include tool type
- [ ] Multiple outputs from cutting are supported
- [ ] Provider integrates with ProviderRegistry (priority ~55)

## References

- GTCEu provider: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- Farmer's Delight GitHub: https://github.com/vectorwing/FarmersDelight

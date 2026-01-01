# Task 04: Ex Nihilo Sequentia Recipe Provider

## Priority: Medium (Tier 2)

## Overview
Implement a custom `ExNihiloRecipeProvider` to extract Ex Nihilo Sequentia recipes with mesh tiers, hammer levels, and fluid transformation rules critical for skyblock progression.

## Mod Details
- **Mod**: Ex Nihilo Sequentia
- **File**: `exnihilosequentia-1.20.1-5.0.0-build.9.jar`
- **Namespace**: `exnihilosequentia`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `meshType` | string | Mesh material (string, flint, iron, diamond, emerald, netherite) |
| `meshTier` | int | Numeric tier of the mesh (1-6) |
| `hammerLevel` | int | Required hammer tier |
| `compostAmount` | float | Fill amount per item (0.0-1.0) |
| `fluidAmount` | int | Millibuckets of fluid produced/consumed |
| `heatSource` | string | Required heat source block |
| `chance` | float | Drop probability (0.0-1.0) |

## Recipe Types to Handle

### Sieve Recipes
- `exnihilosequentia:sieve` - Block sifting with mesh tiers
  - Different drops per mesh type
  - Chance-based outputs

### Hammer Recipes
- `exnihilosequentia:hammer` - Block crushing
  - Tier requirements
  - Multiple outputs

### Composting
- `exnihilosequentia:compost` - Barrel composting
  - Fill amount per item
  - Solid block output

### Fluid Recipes
- `exnihilosequentia:fluid_item` - Item + fluid interactions
- `exnihilosequentia:fluid_on_top` - Fluid layering
- `exnihilosequentia:fluid_transform` - Fluid transformation over time

### Crucible Recipes
- `exnihilosequentia:crucible` - Melting items into fluids
  - Heat source requirements
  - Melt time

### Heat Sources
- `exnihilosequentia:heat` - Heat source registration

## Implementation Notes

1. **API Access**: Ex Nihilo uses `novamachina.exnihilosequentia.api.crafting` package
2. **Mesh Tiers**: Each mesh type unlocks different drops from same input
3. **Stacked Sieves**: Some recipes may require waterlogged sieves
4. **Crucible Types**: Fired vs Unfired crucibles have different capabilities

## Example Output JSON

```json
{
  "id": "exnihilosequentia:sieve/gravel_to_iron",
  "type": "exnihilosequentia:sieve",
  "sourceMod": "exnihilosequentia",
  "machineType": "sieve",
  "input": { "itemId": "minecraft:gravel", "count": 1 },
  "meshType": "flint",
  "meshTier": 2,
  "outputs": [
    { "itemId": "exnihilosequentia:iron_piece", "count": 1, "chance": 0.1 },
    { "itemId": "minecraft:flint", "count": 1, "chance": 0.25 }
  ],
  "waterlogged": false
}
```

```json
{
  "id": "exnihilosequentia:hammer/cobblestone",
  "type": "exnihilosequentia:hammer",
  "sourceMod": "exnihilosequentia",
  "machineType": "hammer",
  "input": { "itemId": "minecraft:cobblestone", "count": 1 },
  "outputs": [
    { "itemId": "minecraft:gravel", "count": 1, "chance": 1.0 }
  ]
}
```

```json
{
  "id": "exnihilosequentia:crucible/cobblestone_to_lava",
  "type": "exnihilosequentia:crucible",
  "sourceMod": "exnihilosequentia",
  "machineType": "fired_crucible",
  "input": { "itemId": "minecraft:cobblestone", "count": 1 },
  "output": { "fluidId": "minecraft:lava", "amount": 250 },
  "meltTime": 200,
  "heatRequired": true
}
```

```json
{
  "id": "exnihilosequentia:compost/wheat_seeds",
  "type": "exnihilosequentia:compost",
  "sourceMod": "exnihilosequentia",
  "machineType": "barrel",
  "input": { "itemId": "minecraft:wheat_seeds", "count": 1 },
  "fillAmount": 0.0625,
  "output": { "itemId": "minecraft:dirt", "count": 1 }
}
```

## Acceptance Criteria

- [ ] Sieve recipes capture mesh type and tier requirements
- [ ] All chance-based outputs are properly represented
- [ ] Hammer recipes include tier requirements
- [ ] Crucible recipes include heat source requirements
- [ ] Compost fill amounts are captured
- [ ] Fluid transformation recipes are included
- [ ] Provider integrates with ProviderRegistry (priority ~65)

## References

- GTCEu provider: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- Ex Nihilo GitHub: https://github.com/NovaMachina-Mods/ExNihiloSequentia

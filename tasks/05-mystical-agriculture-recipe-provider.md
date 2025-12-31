# Task 05: Mystical Agriculture Recipe Provider

## Priority: Medium (Tier 2)

## Overview
Implement a custom `MysticalAgricultureRecipeProvider` to extract Mystical Agriculture's tier-based crafting system, infusion altar recipes, and soul extraction mechanics.

## Mod Details
- **Mods**: Mystical Agriculture, Mystical Agradditions, Mystical Adaptations
- **Files**:
  - `MysticalAgriculture-1.20.1-7.0.23.jar`
  - `MysticalAgradditions-1.20.1-7.0.12.jar`
  - `MysticalAdaptations-1.20.1-1.0.1.jar`
- **Namespaces**: `mysticalagriculture`, `mysticalagradditions`, `mysticaladaptations`

## Custom Data Fields to Capture

| Field | Type | Description |
|-------|------|-------------|
| `cropTier` | int | Crop tier (1-6, or higher with addons) |
| `tierName` | string | Tier name (inferium, prudentium, tertium, imperium, supremium, insanium) |
| `essenceType` | string | Type of essence used |
| `soulCount` | int | Number of souls required |
| `entityType` | string | Entity for soul extraction |
| `infusionInputs` | array | Pedestal inputs for infusion altar |
| `addon` | string | Addon mod that added this recipe (null if base) |

## Recipe Types to Handle

### Infusion Altar
- `mysticalagriculture:infusion` - Main infusion crafting
  - Center input
  - 8 pedestal inputs
  - Essence costs

### Soul Extraction
- `mysticalagriculture:soul_extraction` - Soul jar filling
  - Entity type
  - Souls per kill
  - Required tier

### Seed Crafting
- Tiered seed recipes
- Essence requirements per tier

### Awakening
- `mysticalagriculture:awakening` - Awakening altar recipes
  - Similar to infusion but for supremium+ items

### Reprocessing
- `mysticalagriculture:reprocessor` - Seed reprocessing
  - Tier-based output amounts

## Implementation Notes

1. **API Access**: Mystical Agriculture uses `com.blakebr0.mysticalagriculture.api` package
2. **Tier System**: Higher tiers require exponentially more essences
3. **Infusion Altar**: 8 pedestals arranged around central altar
4. **Soul System**: Different mobs give different soul types

## Example Output JSON

```json
{
  "id": "mysticalagriculture:infusion/iron_seeds",
  "type": "mysticalagriculture:infusion",
  "sourceMod": "mysticalagriculture",
  "machineType": "infusion_altar",
  "tier": 2,
  "tierName": "prudentium",
  "centerInput": { "itemId": "mysticalagriculture:prosperity_seed_base", "count": 1 },
  "pedestalInputs": [
    { "itemId": "minecraft:iron_ingot", "count": 1 },
    { "itemId": "minecraft:iron_ingot", "count": 1 },
    { "itemId": "mysticalagriculture:prudentium_essence", "count": 1 },
    { "itemId": "mysticalagriculture:prudentium_essence", "count": 1 },
    { "itemId": "mysticalagriculture:iron_essence", "count": 1 },
    { "itemId": "mysticalagriculture:iron_essence", "count": 1 },
    { "itemId": "mysticalagriculture:iron_essence", "count": 1 },
    { "itemId": "mysticalagriculture:iron_essence", "count": 1 }
  ],
  "output": { "itemId": "mysticalagriculture:iron_seeds", "count": 1 }
}
```

```json
{
  "id": "mysticalagriculture:soul_extraction/zombie",
  "type": "mysticalagriculture:soul_extraction",
  "sourceMod": "mysticalagriculture",
  "entityType": "minecraft:zombie",
  "soulsPerKill": 2,
  "soulJarOutput": { "itemId": "mysticalagriculture:zombie_soul_jar", "count": 1 },
  "totalSoulsRequired": 64
}
```

```json
{
  "id": "mysticalagriculture:reprocessor/tier3_seeds",
  "type": "mysticalagriculture:reprocessor",
  "sourceMod": "mysticalagriculture",
  "machineType": "seed_reprocessor",
  "input": { "itemId": "mysticalagriculture:tertium_seeds", "count": 1 },
  "outputs": [
    { "itemId": "mysticalagriculture:tertium_essence", "count": 2 }
  ],
  "tier": 3
}
```

## Acceptance Criteria

- [ ] Infusion altar recipes capture all 8 pedestal inputs
- [ ] Tier requirements are properly captured
- [ ] Soul extraction includes entity type and soul counts
- [ ] Reprocessor recipes include tier-based output scaling
- [ ] Awakening altar recipes are included
- [ ] Addon recipes are captured with `addon` field set appropriately
- [ ] Provider integrates with ProviderRegistry (priority ~60)

## References

- GTCEu provider: `forge-1.20.1/src/main/java/com/recipeflow/mod/v120plus/provider/GTCEuRecipeProvider.java`
- Mystical Agriculture GitHub: https://github.com/BlakeBr0/MysticalAgriculture

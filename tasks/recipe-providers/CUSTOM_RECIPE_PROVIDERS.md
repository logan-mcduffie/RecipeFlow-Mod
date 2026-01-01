# Mods Requiring Custom Recipe Providers

This document lists mods from the Star Technology modpack that require custom `RecipeProvider` implementations due to having unique recipe systems with mod-specific data that the standard recipe extraction cannot capture.

## Summary

| # | Provider | Mods Covered | Priority |
|---|----------|--------------|----------|
| 01 | Create | Create + 7 addons | High |
| 02 | Thermal | Thermal Series + 2 addons | High |
| 03 | AE2 | Applied Energistics 2 + 7 addons | High |
| 04 | Ex Nihilo | Ex Nihilo Sequentia | Medium |
| 05 | Mystical Agriculture | Mystical Agriculture + 2 addons | Medium |
| 06 | Farmer's Delight | Farmer's Delight | Medium |

**Note**: GTCEu is already implemented.

---

## Tier 1: High Priority

### 01 - Create + Addons
Custom processing with stress units, RPM, heat levels, sequenced assembly.

**Mods handled**:
- Create (base)
- Create: Diesel Generators
- Create: New Age
- Create: Low Heated
- Steam & Rails
- Copycats
- Create Hypertube
- Vintage

### 02 - Thermal Series + Addons
RF energy costs, catalysts, chanced outputs, dynamo fuels.

**Mods handled**:
- Thermal Foundation/Expansion/Cultivation (base)
- Thermal Extra
- Systeams

### 03 - Applied Energistics 2 + Addons
Inscriber slot-based crafting, charger recipes, in-world transformations.

**Mods handled**:
- Applied Energistics 2 (base)
- AE2 Wireless Terminals
- Expanded AE
- Extended AE
- Mega Cells
- AE2 Crafting Terminal
- Better P2P
- ME Requester

---

## Tier 2: Medium Priority

### 04 - Ex Nihilo Sequentia
Sieve mesh tiers, hammer levels, crucible heat sources, composting.

### 05 - Mystical Agriculture + Addons
Tier-based seeds, infusion altar, soul extraction, awakening altar.

**Mods handled**:
- Mystical Agriculture (base)
- Mystical Agradditions
- Mystical Adaptations

### 06 - Farmer's Delight
Cooking pot recipes, cutting board tool requirements.

---

## Mods Using Standard Recipes

The following mods use standard vanilla-style recipes and are handled by `StandardRecipeProvider`:

- **Pipes/Transfer**: Pipez, LaserIO, Modular Routers
- **Storage**: Functional Storage, Tom's Storage, Ender Chests/Tanks
- **Energy**: Solar Flux Reborn, Flux Networks
- **Decoration**: Chipped, Architects Palette, Framed Blocks, etc.
- **Utilities**: All other utility/QoL mods

---

## Data Fields Summary

| Provider | Critical Fields |
|----------|-----------------|
| Create | `stressImpact`, `requiredRPM`, `processingTime`, `heatLevel`, `sequenceSteps[]`, `addon` |
| Thermal | `energyCost` (RF), `catalyst`, `catalystChance`, `steamCost`, `addon` |
| AE2 | `inscriber.top/middle/bottom`, `consumesTop/Bottom`, `pressType`, `addon` |
| Ex Nihilo | `meshTier`, `hammerLevel`, `compostAmount`, `heatRequired` |
| Mystical Ag | `cropTier`, `tierName`, `pedestalInputs[]`, `entityType`, `addon` |
| Farmer's Delight | `cookingTime`, `container`, `tool`, `heatSourceRequired` |

---

## Implementation Notes

- Each provider sets an `addon` field to identify which mod added a recipe
- Addon recipes use the same recipe types as their parent mod
- Provider priorities: GTCEu (100), Create (80), Thermal (75), AE2 (70), others (60-55)
- See individual task files in `/tasks/` for detailed specifications
- GTCEu is already implemented and serves as the reference implementation

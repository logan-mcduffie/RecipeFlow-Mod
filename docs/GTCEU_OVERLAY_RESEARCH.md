# GTCEu Modern Overlay Rendering Research

## Overview

This document contains research findings on how GregTech CEu Modern (GTCEu) handles machine overlay textures, specifically for animated overlays on Input/Output Buses and Hatches.

**Key Finding**: GTCEu does NOT use runtime vertex color tinting for input/output colors. Instead, it uses **separate pre-colored texture files** for input (green) and output (red) variants.

---

## Texture Atlas Scan Results

Running `/recipeflow scangtceu` reveals the actual animated textures available in GTCEu:

### Animated Overlays (frame count > 1)

| Texture Path | Frames | Purpose |
|--------------|--------|---------|
| `overlay_pipe_in` | 6 | **GREEN** - Input machines (buses, hatches) |
| `overlay_pipe_out` | 6 | **RED** - Output machines (buses, hatches) |
| `overlay_item_output` | 6 | Item output indicator |
| `overlay_fluid_output` | 6 | Fluid output indicator |
| `overlay_buffer` | 4 | Buffer machines |
| `overlay_buffer_emissive` | 4 | Buffer machines (emissive) |
| `overlay_screen_emissive` | 4 | Generic machine screens |
| `overlay_blower_active` | 4 | Blower machines |
| `overlay_qchest_emissive` | 4 | Quantum Chest |
| `overlay_qtank_emissive` | 4 | Quantum Tank |
| `overlay_creativecontainer_emissive` | 14 | Creative containers |

### Static Overlays (1 frame)

| Texture Path | Purpose |
|--------------|---------|
| `overlay_pipe` | Base pipe overlay |
| `overlay_item_hatch` | Item hatch base |
| `overlay_item_hatch_input` | Item input hatch (static, pre-colored green) |
| `overlay_item_hatch_output` | Item output hatch (static, pre-colored red) |
| `overlay_fluid_hatch` | Fluid hatch base |
| `overlay_fluid_hatch_input` | Fluid input hatch (static) |
| `overlay_fluid_hatch_output` | Fluid output hatch (static) |
| `overlay_screen` | Machine screen base |
| `overlay_dual_hatch` | Dual hatch base |

**Base Path**: `gtceu:block/overlay/machine/`

---

## GTCEu Machine Registration

From `GTMachines.java`, machines are registered with overlay configurations:

### Input Bus
```java
public static final MachineDefinition[] ITEM_IMPORT_BUS = registerTieredMachines(
    "input_bus",
    (holder, tier) -> new ItemBusPartMachine(holder, tier, IN),
    (tier, builder) -> builder
        .colorOverlayTieredHullModel(
            OVERLAY_ITEM_HATCH_INPUT,     // "overlay_item_hatch_input" (static)
            "overlay_pipe",                // base pipe
            "overlay_pipe_in_emissive")    // animated green (but actually "overlay_pipe_in")
        ...
```

### Output Bus
```java
public static final MachineDefinition[] ITEM_EXPORT_BUS = registerTieredMachines(
    "output_bus",
    (holder, tier) -> new ItemBusPartMachine(holder, tier, OUT),
    (tier, builder) -> builder
        .colorOverlayTieredHullModel(
            OVERLAY_ITEM_HATCH_OUTPUT,    // "overlay_item_hatch_output" (static)
            "overlay_pipe",                // base pipe
            "overlay_pipe_out_emissive")   // animated red (but actually "overlay_pipe_out")
        ...
```

**Important**: The code references `overlay_pipe_in_emissive` and `overlay_pipe_out_emissive`, but the actual texture atlas contains `overlay_pipe_in` and `overlay_pipe_out` (without `_emissive` suffix).

---

## Model Structure

From `hatch_machine_emissive.json`:

```json
{
  "elements": [
    // Main block with tintindex: 1 (for painting color)
    { "faces": { "all": { "tintindex": 1 } } },

    // Overlay layers (NO color tinting)
    { "name": "overlay_pipe", "texture": "#overlay_pipe" },
    { "name": "overlay", "texture": "#overlay" },
    { "name": "overlay_emissive", "texture": "#overlay_emissive",
      "tintindex": -101, "block_light": 15 }  // -101 is for shimmer/emission
  ]
}
```

### Tint Index Usage

| Index | Purpose |
|-------|---------|
| 1 | Machine hull/base painting color |
| 2 | Some overlay tinted layers (energy hatches) |
| 9 | ItemBusPartMachine specific color |
| -101 | Emissive/shimmer effects |
| -111 | Emission with shimmer mod |

---

## GTCEu API Access

### Key Classes

| Class | Purpose |
|-------|---------|
| `MetaMachineItem` | Item class for GT machines |
| `MachineDefinition` | Contains machine configuration |
| `GTRegistries.MACHINES` | Registry of all machine definitions |

### Getting MachineDefinition from ItemStack

```java
if (stack.getItem() instanceof MetaMachineItem machineItem) {
    MachineDefinition definition = machineItem.getDefinition();
    String machineId = definition.getId().getPath();
    // machineId examples: "lv_input_bus", "mv_output_hatch", etc.
}
```

---

## Color Application Summary

1. **Overlay colors are NOT applied at runtime** via vertex tinting
2. **Colors are baked into separate PNG texture files**:
   - `overlay_pipe_in.png` - Pre-colored GREEN (6 frames animated)
   - `overlay_pipe_out.png` - Pre-colored RED (6 frames animated)
3. **The static overlays** (`overlay_item_hatch_input`, `overlay_item_hatch_output`) are also pre-colored but NOT animated
4. **Machine hull tinting** uses `tintindex: 1` for painting colors, but this doesn't affect overlays

---

## Implementation for RecipeFlow

### Correct Approach

1. Detect if item is a GTCEu machine via `MetaMachineItem` instanceof check
2. Get machine ID from `MachineDefinition`
3. Map machine type to correct overlay texture:
   - Input machines → `overlay_pipe_in` (GREEN, 6 frames)
   - Output machines → `overlay_pipe_out` (RED, 6 frames)
4. Load sprite directly from texture atlas
5. Extract animation frames from sprite

### Machine ID Pattern Matching

```java
String machineId = definition.getId().getPath().toLowerCase();

if (machineId.contains("input") || machineId.contains("import")) {
    // Use overlay_pipe_in (GREEN)
} else if (machineId.contains("output") || machineId.contains("export")) {
    // Use overlay_pipe_out (RED)
}
```

---

## File Locations in GTCEu

### Source Code (GitHub)
- Repository: `https://github.com/GregTechCEu/GregTech-Modern`
- Branch: `1.20.1`

### Key Files

| File | Purpose |
|------|---------|
| `GTMachines.java` | Machine definitions with overlay configuration |
| `GTMachineModels.java` | Overlay constants and model building |
| `ItemBusPartMachine.java` | Machine implementation with IO type |
| `MachineModel.java` | Runtime overlay sprite loading |

### Texture Locations
- `src/main/resources/assets/gtceu/textures/block/overlay/machine/`
- Animation metadata: `.mcmeta` files alongside textures

---

## Animation Metadata

Example from `overlay_pipe_in.png.mcmeta`:
```json
{
  "animation": {
    "frametime": 2
  }
}
```

- `frametime: 2` = 2 game ticks per frame = 100ms per frame
- Frames are stored as vertical strip in the PNG
- Frame height = texture_height / frame_count

---

## Troubleshooting

### Common Issues

1. **Sprite returns `missingno`**: Texture path is wrong or texture not loaded in atlas
2. **Sprite has 1 frame**: Using static overlay instead of animated one
3. **Wrong color**: Using wrong overlay (e.g., `overlay_pipe_in` vs `overlay_pipe_out`)

### Debug Commands

```
/recipeflow scangtceu     - Scan all GTCEu overlay textures
/recipeflow debugicon <item_id> - Debug specific item rendering
```

---

## Version Compatibility

- GTCEu Modern 1.6.x (current target)
- Minecraft 1.20.1
- Forge

**Note**: Texture naming may vary between GTCEu versions. The scan command helps discover actual available textures.

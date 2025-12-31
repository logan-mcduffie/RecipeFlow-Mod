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

---

## Deep Dive: GTCEu Rendering Pipeline

This section documents the complete rendering architecture for GTCEu machines, particularly relevant for understanding why runtime texture atlas manipulation doesn't work for capturing animation frames.

### Architecture Overview

GTCEu uses a **multi-layer JSON model system** with runtime texture overrides:

```
JSON Model Template (sided.json)
    ↓
Model Baking (creates BakedQuads with UV coordinates)
    ↓
BakedQuadMixin (stores texture key like "#overlay_front_emissive")
    ↓
TextureOverrideModel.retextureQuads() (swaps sprites at runtime)
    ↓
GTQuadTransformers.setSprite() (remaps UVs to new sprite bounds)
    ↓
Final Rendered Quad
```

### Key Source Files

| File | Location | Purpose |
|------|----------|---------|
| `sided.json` | `resources/assets/gtceu/models/block/machine/template/sided/` | Template model defining 3 elements: base, overlay, emissive |
| `MachineModel.java` | `client/model/machine/` | Main baked model implementation |
| `TextureOverrideModel.java` | `client/model/` | Runtime texture swapping wrapper |
| `GTQuadTransformers.java` | `client/util/` | UV remapping utilities |
| `WorkableOverlays.java` | `client/model/machine/overlays/` | Overlay texture definitions per status |
| `GTMachineModels.java` | `common/data/models/` | Model configuration and overlay setup |
| `BakedQuadMixin.java` | `core/mixins/client/` | Mixin adding texture key tracking |

### JSON Model Structure (sided.json)

The template defines **3 separate block elements** with different offsets:

```json
{
  "elements": [
    {
      "comment": "Element 1: Base block",
      "from": [0, 0, 0],
      "to": [16, 16, 16],
      "faces": {
        "down": { "texture": "#bottom", "tintindex": 1 }
        // ... tiered casing texture with tint support
      }
    },
    {
      "comment": "Element 2: Regular overlay (slightly larger)",
      "from": [-0.01, -0.01, -0.01],
      "to": [16.01, 16.01, 16.01],
      "faces": {
        "north": { "texture": "#overlay_front" }
        // ... overlay textures
      }
    },
    {
      "comment": "Element 3: Emissive overlay (even larger, with lighting)",
      "from": [-0.02, -0.02, -0.02],
      "to": [16.02, 16.02, 16.02],
      "forge_data": { "block_light": 15, "sky_light": 15 },
      "shade": false,
      "faces": {
        "north": { "texture": "#overlay_front_emissive", "tintindex": -101 }
        // ... emissive textures with full brightness
      }
    }
  ]
}
```

**Emissive overlay properties:**
- `forge_data` with `block_light: 15, sky_light: 15` = full brightness
- `shade: false` = ignores ambient occlusion
- `tintindex: -101` = prevents tinting, triggers shimmer effects

### BakedQuadMixin - Texture Key Tracking

GTCEu adds a custom field to `BakedQuad` via mixin:

```java
@Mixin(BakedQuad.class)
public class BakedQuadMixin implements IGTBakedQuad {
    @Unique
    private String gtceu$textureKey = null;  // e.g., "#overlay_front_emissive"

    @Override
    public BakedQuad gtceu$setTextureKey(@Nullable String key) { ... }

    @Override
    public String gtceu$getTextureKey() { return gtceu$textureKey; }
}
```

This allows GTCEu to track which texture reference each quad uses, enabling runtime texture swapping.

### TextureOverrideModel - Runtime Texture Swapping

When rendering, `TextureOverrideModel.retextureQuads()` replaces sprites:

```java
public static List<BakedQuad> retextureQuads(List<BakedQuad> quads,
                                              Map<String, TextureAtlasSprite> overrides) {
    List<BakedQuad> newQuads = new LinkedList<>();
    for (BakedQuad quad : quads) {
        String textureKey = quad.gtceu$getTextureKey();  // e.g., "overlay_front_emissive"
        TextureAtlasSprite replacement = overrides.get(textureKey);
        if (replacement != null) {
            newQuads.add(GTQuadTransformers.setSprite(quad, replacement));
        } else {
            newQuads.add(quad);
        }
    }
    return newQuads;
}
```

### GTQuadTransformers.setSprite() - UV Remapping

**This is the critical function** that explains why our animation capture approach fails:

```java
public static BakedQuad setSprite(BakedQuad quad, TextureAtlasSprite sprite) {
    TextureAtlasSprite oldSprite = quad.getSprite();
    int[] vertices = quad.getVertices().clone();

    for (int i = 0; i < 4; i++) {
        int offset = i * IQuadTransformer.STRIDE + IQuadTransformer.UV0;
        float u = Float.intBitsToFloat(vertices[offset]);
        float v = Float.intBitsToFloat(vertices[offset + 1]);

        // UV coordinates are remapped from old sprite bounds to new sprite bounds
        // Uses sprite.getU0(), getU1(), getV0(), getV1() - FULL SPRITE BOUNDS
        u = Mth.map(u, oldSprite.getU0(), oldSprite.getU1(),
                       sprite.getU0(), sprite.getU1());
        v = Mth.map(v, oldSprite.getV0(), oldSprite.getV1(),
                       sprite.getV0(), sprite.getV1());

        vertices[offset] = Float.floatToRawIntBits(u);
        vertices[offset + 1] = Float.floatToRawIntBits(v);
    }

    return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(),
            sprite, quad.isShade(), quad.hasAmbientOcclusion());
}
```

**Key insight**: UV coordinates are calculated using `sprite.getU0()`, `getU1()`, `getV0()`, `getV1()` which represent the **entire sprite bounds** in the texture atlas - these point to **frame 0 only**, not a specific animation frame.

### WorkableOverlays - Status-Based Texture Selection

For machines with multiple states (idle, working, suspended), `WorkableOverlays` manages texture selection:

```java
public class WorkableOverlays {
    public static class StatusTextures {
        private final Map<Status, ResourceLocation> textures = new EnumMap<>(Status.class);
        private final Map<Status, ResourceLocation> emissiveTextures = new EnumMap<>(Status.class);

        public StatusTextures(ResourceLocation normalSprite,
                              ResourceLocation activeSprite,
                              ResourceLocation pausedSprite,
                              ResourceLocation normalSpriteEmissive,
                              ResourceLocation activeSpriteEmissive,
                              ResourceLocation pausedSpriteEmissive) {
            textures.put(Status.IDLE, normalSprite);
            textures.put(Status.WORKING, activeSprite);
            textures.put(Status.SUSPEND, pausedSprite);
            // ... emissive variants
        }

        public ResourceLocation getEmissiveTexture(Status status) {
            return emissiveTextures.getOrDefault(status, BLANK_TEXTURE);
        }
    }
}
```

### GTMachineModels.addWorkableOverlays()

This function sets up the texture references in the model:

```java
public static ConfiguredModel[] addWorkableOverlays(WorkableOverlays overlays,
                                                    RecipeLogic.Status status,
                                                    BlockModelBuilder model) {
    for (var entry : overlays.getTextures().entrySet()) {
        var face = entry.getKey();
        var textures = entry.getValue();

        ResourceLocation overlay = textures.getTexture(status);
        ResourceLocation overlayEmissive = textures.getEmissiveTexture(status);

        if (overlay != BLANK_TEXTURE) {
            model.texture(OVERLAY_PREFIX + face.getName(), overlay);
        }
        if (overlayEmissive != BLANK_TEXTURE) {
            model.texture(OVERLAY_PREFIX + face.getName() + EMISSIVE_SUFFIX, overlayEmissive);
        }
    }
    return ConfiguredModel.builder().modelFile(model).build();
}
```

---

## Fusion Reactor Specifics

The fusion reactor is defined in `GTMultiMachines.java`:

```java
public static final MultiblockMachineDefinition[] FUSION_REACTOR =
    registerTieredMultis("fusion_reactor", FusionReactorMachine::new, (tier, builder) -> builder
        // ...
        .model(createWorkableCasingMachineModel(
            FusionReactorMachine.getCasingType(tier).getTexture(),
            GTCEu.id("block/multiblock/fusion_reactor")))
        // ...
    );
```

### Fusion Reactor Textures

Location: `assets/gtceu/textures/block/multiblock/fusion_reactor/`

| File | Frames | Animation |
|------|--------|-----------|
| `overlay_front.png` | 1 | Static (idle) |
| `overlay_front_emissive.png` | 4 | Animated (idle emissive) |
| `overlay_front_active.png` | 1 | Static (active) |
| `overlay_front_active_emissive.png` | 4 | **Animated (8 ticks/frame = 400ms)** |

Animation metadata (`overlay_front_active_emissive.png.mcmeta`):
```json
{ "animation": { "frametime": 8 } }
```

- 8 ticks per frame = 400ms per frame
- 4 frames = 1600ms total cycle

---

## Why Runtime uploadFrame() Doesn't Work

When we call `AnimatedTexture.uploadFrame(x, y, frameIndex)`:

1. ✅ It uploads the pixel data for frame N to the GPU texture at position (x, y)
2. ❌ **BUT** the BakedQuad's UV coordinates were calculated during model baking
3. ❌ These UVs point to `sprite.getU0()` to `sprite.getU1()` - frame 0's bounds
4. ❌ The UV coordinates **don't change** when we upload different frames
5. ❌ The GPU reads from the same UV location regardless of what frame data is there

**How normal animation works:**
- `SpriteTicker.tickAndUpload()` is called every game tick
- It uploads the current frame to the **same atlas position** (frame 0's location)
- The UVs still point to the same location, so new pixel data is displayed
- The animation appears to work because pixel data at frame 0's position changes

**Our problem:**
- We're correctly uploading different frame data to the atlas
- But something in the GTCEu pipeline is caching or bypassing our uploads
- For item rendering specifically, the texture override system may behave differently

---

## Viable Solutions for Animation Capture

### Option A: Direct Frame Extraction (Recommended)

Extract animation frames directly from the source `NativeImage`:

```java
public List<BufferedImage> captureAnimatedFrames(ItemStack stack,
                                                  TextureAtlasSprite emissiveSprite) {
    // 1. Render base item (single frame)
    BufferedImage baseRender = renderItem(stack);

    // 2. Get source image from sprite contents (via reflection)
    SpriteContents contents = emissiveSprite.contents();
    NativeImage sourceImage = getOriginalImage(contents);  // reflection
    FrameTimingInfo timings = SpriteAnimationMetadata.getFrameTimings(emissiveSprite);

    // 3. For each animation frame, extract and composite
    List<BufferedImage> frames = new ArrayList<>();
    int frameHeight = sourceImage.getHeight() / timings.frameCount();

    for (int i = 0; i < timings.frameCount(); i++) {
        // Extract frame i from vertical strip
        BufferedImage framePixels = extractFrame(sourceImage, i,
                                                  sourceImage.getWidth(), frameHeight);

        // Composite onto base render at correct screen position
        BufferedImage composited = composite(baseRender, framePixels, overlayPosition);
        frames.add(composited);
    }

    return frames;
}
```

**Pros:** Works regardless of GTCEu's rendering quirks
**Cons:** Need to calculate overlay position on rendered item

### Option B: Pre-render All Machine States

For workable machines, pre-render each state variant:

```java
// Get all state variants from the model
Map<MachineRenderState, BakedModel> modelsByState = machineModel.getModelsByState();

for (var entry : modelsByState.entrySet()) {
    MachineRenderState state = entry.getKey();
    // Render with specific state's model
    BufferedImage stateRender = renderWithModel(stack, entry.getValue());
}
```

**Pros:** Uses GTCEu's own state system
**Cons:** Only gives different states, not animation frames within a state

### Option C: Hook TextureOverrideModel

Intercept the sprite lookup to return frame-specific sprites:

```java
// Before rendering frame N
Map<String, TextureAtlasSprite> frameOverrides = new HashMap<>(originalOverrides);
frameOverrides.put("overlay_front_emissive", createFrameSprite(originalSprite, frameN));

// Render with modified overrides
renderWithOverrides(stack, frameOverrides);
```

**Pros:** Uses existing pipeline
**Cons:** Complex, requires creating virtual sprites with modified UV bounds

---

## Debugging Commands

```
/recipeflow scangtceu          - Scan all GTCEu overlay textures in atlas
/recipeflow debugicon <item>   - Debug specific item rendering
/recipeflow debuganimation     - Show animation metadata for held item
```

---

## References

- GTCEu Repository: https://github.com/GregTechCEu/GregTech-Modern
- SRG Name Lookup: https://linkie.shedaniel.dev/
- Forge ObfuscationReflectionHelper documentation

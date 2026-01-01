# Task: Build Sprite Sheet + Metadata Export System

## Overview

Replace GIF-based icon export with a sprite sheet + metadata approach. Instead of pre-rendering animations as GIFs, export the raw sprite data and let the website render animations client-side using WebGL/Canvas.

## Benefits

- **Tiny file size**: Just the sprite sheet PNG + JSON metadata vs. massive GIFs
- **Perfect accuracy**: Uses actual mcmeta timing from the game
- **Flexibility**: Website can pause, scrub, speed up animations
- **Compositing**: Website handles layering (base + overlay + tint)

## Export Format

### Static Items

For non-animated items, export a single PNG:

```json
{
  "id": "minecraft:diamond",
  "type": "static",
  "filename": "minecraft_diamond.png",
  "size": 128
}
```

### Animated Items

For animated items, export sprite sheet + animation metadata:

```json
{
  "id": "gtceu:max_input_bus",
  "type": "animated",
  "spriteSheet": "gtceu_max_input_bus_sprites.png",
  "size": 128,
  "layers": [
    {
      "name": "base",
      "sprite": "gtceu:block/casings/voltage/max/side",
      "region": { "x": 0, "y": 0, "width": 128, "height": 1280 },
      "frameHeight": 128,
      "uniqueFrameCount": 10,
      "animation": {
        "frames": [
          { "index": 0, "time": 60 },
          { "index": 1, "time": 2 },
          { "index": 2, "time": 2 },
          { "index": 3, "time": 2 },
          { "index": 2, "time": 2 },
          { "index": 1, "time": 2 }
        ],
        "interpolate": false
      }
    },
    {
      "name": "overlay",
      "sprite": "gtceu:block/overlay/machine/overlay_pipe_in_emissive",
      "region": { "x": 128, "y": 0, "width": 128, "height": 768 },
      "frameHeight": 128,
      "uniqueFrameCount": 6,
      "emissive": true,
      "animation": {
        "frames": [
          { "index": 0, "time": 2 },
          { "index": 1, "time": 2 },
          { "index": 2, "time": 2 },
          { "index": 3, "time": 2 },
          { "index": 4, "time": 2 },
          { "index": 5, "time": 2 }
        ],
        "interpolate": false
      }
    }
  ],
  "tint": {
    "type": "gtceu_rainbow",
    "cycleTicks": 288
  }
}
```

### Sprite Sheet Layout

The sprite sheet PNG contains all unique frames for all layers, packed efficiently:

```
+------------------+------------------+
|  Base Frame 0    |  Overlay Frame 0 |
+------------------+------------------+
|  Base Frame 1    |  Overlay Frame 1 |
+------------------+------------------+
|  Base Frame 2    |  Overlay Frame 2 |
+------------------+------------------+
|  ...             |  ...             |
+------------------+------------------+
```

## Implementation Details

### New Classes

1. **SpriteSheetBuilder.java**
   - Packs multiple sprite frames into a single PNG
   - Tracks regions for each layer
   - Handles different frame counts per layer

2. **AnimationMetadataBuilder.java**
   - Extracts frame timing from SpriteAnimationMetadata
   - Detects tint types (rainbow, static, none)
   - Builds JSON structure for web consumption

3. **IconSpriteExporter.java** (replaces IconExporter for icons)
   - Orchestrates sprite sheet + metadata export
   - Handles static vs animated items
   - Batches exports for performance

### Reuse Existing Code

Keep and use:
- `SpriteAnimationMetadata.java` - Frame timing extraction
- `DirectFrameExtractor.java` - Extracting frames from NativeImage
- `ObfuscationHelper.java` - Reflection utilities
- `GTCEuIconHelper.java` - GTCEu-specific detection
- `SimpleRenderModeHelper.java` - GTCEu render mode control

### Remove/Simplify

- Remove GIF-specific code from `IconExporter.java`
- Remove multiple rendering approaches from `AnimatedIconRenderer.java`
- Keep only `renderItem()` for static renders

## Files to Create

- `util/export/SpriteSheetBuilder.java`
- `util/export/AnimationMetadataBuilder.java`
- `util/export/IconSpriteExporter.java`

## Files to Modify

- `util/AnimatedIconRenderer.java` - Remove dead rendering methods
- `util/IconExporter.java` - Remove GIF code, delegate to new exporter
- `command/SyncCommand.java` - Use new export system

## Acceptance Criteria

- [ ] Static items export as single PNG + metadata entry
- [ ] Animated items export as sprite sheet PNG + full animation metadata
- [ ] Multi-layer items (base + overlay) packed into single sprite sheet
- [ ] Tint information included in metadata (type, cycle duration)
- [ ] Frame timing matches actual mcmeta data
- [ ] Exported sprite sheets are reasonably sized (< 100KB typical)
- [ ] Build compiles with no dead code warnings

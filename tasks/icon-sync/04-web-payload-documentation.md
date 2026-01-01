# Task: Web App Payload Documentation

## Overview

This document specifies the exact payload format the mod sends to the web application, enabling the web team to implement client-side icon rendering using WebGL/Canvas.

## Payload Structure

The mod uploads icon data as a JSON payload with embedded base64 sprite sheets.

### Top-Level Structure

```json
{
  "version": "1.2.3",
  "modpackSlug": "atm9",
  "timestamp": 1704067200000,
  "manifestHash": "sha256:abc123...",
  "icons": {
    "items": [ ... ],
    "fluids": [ ... ]
  },
  "metadata": {
    "totalItems": 15234,
    "totalFluids": 892,
    "animatedCount": 456,
    "staticCount": 15670
  }
}
```

## Icon Entry Types

### Static Icon

Non-animated items/blocks/fluids. Single PNG image.

```json
{
  "id": "minecraft:diamond",
  "type": "static",
  "size": 128,
  "data": "iVBORw0KGgoAAAANSUhEUgAA..."
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Full item/fluid ID (e.g., `minecraft:diamond`) |
| `type` | `"static"` | Indicates non-animated icon |
| `size` | number | Icon dimension in pixels (always square) |
| `data` | string | Base64-encoded PNG image data |

### Animated Icon (Simple)

Animated items with a single sprite layer and no tint.

```json
{
  "id": "minecraft:magma_block",
  "type": "animated",
  "size": 128,
  "spriteSheet": "iVBORw0KGgoAAAANSUhEUgAA...",
  "animation": {
    "frameCount": 3,
    "frameHeight": 128,
    "frames": [
      { "index": 0, "time": 20 },
      { "index": 1, "time": 20 },
      { "index": 2, "time": 20 }
    ],
    "interpolate": false
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `spriteSheet` | string | Base64 PNG with frames stacked vertically |
| `animation.frameCount` | number | Total unique frames in sprite sheet |
| `animation.frameHeight` | number | Height of each frame in pixels |
| `animation.frames` | array | Frame sequence with timing |
| `animation.frames[].index` | number | Frame index in sprite sheet (0-based) |
| `animation.frames[].time` | number | Duration in game ticks (1 tick = 50ms) |
| `animation.interpolate` | boolean | Whether to smoothly blend between frames |

### Animated Icon (Multi-Layer)

Complex animated items with multiple sprite layers (e.g., GTCEu machines with base + overlay).

```json
{
  "id": "gtceu:max_input_bus",
  "type": "animated",
  "size": 128,
  "spriteSheet": "iVBORw0KGgoAAAANSUhEUgAA...",
  "layers": [
    {
      "name": "base",
      "region": { "x": 0, "y": 0, "width": 128, "height": 1280 },
      "frameHeight": 128,
      "frameCount": 10,
      "animation": {
        "frames": [
          { "index": 0, "time": 60 },
          { "index": 1, "time": 2 },
          { "index": 2, "time": 2 },
          { "index": 3, "time": 2 },
          { "index": 2, "time": 2 },
          { "index": 1, "time": 2 },
          { "index": 0, "time": 60 }
        ],
        "interpolate": false
      }
    },
    {
      "name": "overlay",
      "region": { "x": 128, "y": 0, "width": 128, "height": 768 },
      "frameHeight": 128,
      "frameCount": 6,
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
    "cycleTicks": 288,
    "affectsLayers": ["base"]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `layers` | array | Ordered list of sprite layers (bottom to top) |
| `layers[].name` | string | Layer identifier |
| `layers[].region` | object | Rectangle in sprite sheet for this layer |
| `layers[].frameHeight` | number | Height of each frame within the region |
| `layers[].frameCount` | number | Unique frames for this layer |
| `layers[].emissive` | boolean | If true, render with additive blending / glow |
| `layers[].animation` | object | Frame sequence (same format as simple) |
| `tint` | object | Color tinting configuration |
| `tint.type` | string | Tint algorithm (see Tint Types below) |
| `tint.cycleTicks` | number | Full color cycle duration in ticks |
| `tint.affectsLayers` | array | Which layers receive the tint |

## Tint Types

### `none`
No tinting applied.

### `static`
Single static color applied to affected layers.
```json
{
  "type": "static",
  "color": "#FF5500",
  "affectsLayers": ["base"]
}
```

### `gtceu_rainbow`
GTCEu-style rainbow cycling. Hue rotates through full spectrum.
```json
{
  "type": "gtceu_rainbow",
  "cycleTicks": 288,
  "affectsLayers": ["base"]
}
```

**Algorithm:**
```javascript
function getGTCEuRainbowColor(tickTime) {
  // Full hue rotation over cycleTicks
  const hue = (tickTime % 288) / 288;
  return hslToRgb(hue, 1.0, 0.5);
}
```

## Fluid Icons

Fluids follow the same structure but include additional fluid-specific metadata:

```json
{
  "id": "minecraft:water",
  "type": "animated",
  "size": 128,
  "fluidType": "source",
  "spriteSheet": "iVBORw0KGgoAAAANSUhEUgAA...",
  "tint": {
    "type": "static",
    "color": "#3F76E4"
  },
  "animation": {
    "frameCount": 32,
    "frameHeight": 128,
    "frames": [ ... ],
    "interpolate": false
  }
}
```

## Web Rendering Implementation

### Canvas 2D Approach

```javascript
class IconRenderer {
  constructor(canvas, iconData) {
    this.ctx = canvas.getContext('2d');
    this.icon = iconData;
    this.spriteSheet = new Image();
    this.spriteSheet.src = 'data:image/png;base64,' + iconData.spriteSheet;
    this.elapsed = 0;
  }

  render(deltaTime) {
    this.elapsed += deltaTime;

    if (this.icon.type === 'static') {
      this.ctx.drawImage(this.spriteSheet, 0, 0);
      return;
    }

    // Calculate current frame for each layer
    for (const layer of this.icon.layers || [this.icon]) {
      const frameIndex = this.getFrameAtTime(layer.animation, this.elapsed);
      const srcY = frameIndex * layer.frameHeight;

      if (layer.emissive) {
        this.ctx.globalCompositeOperation = 'lighter';
      }

      this.ctx.drawImage(
        this.spriteSheet,
        layer.region?.x || 0,
        (layer.region?.y || 0) + srcY,
        layer.region?.width || this.icon.size,
        layer.frameHeight,
        0, 0,
        this.icon.size, this.icon.size
      );

      this.ctx.globalCompositeOperation = 'source-over';
    }

    // Apply tint if needed
    if (this.icon.tint?.type === 'gtceu_rainbow') {
      this.applyRainbowTint();
    }
  }

  getFrameAtTime(animation, elapsedMs) {
    const totalTicks = animation.frames.reduce((sum, f) => sum + f.time, 0);
    const ticksElapsed = (elapsedMs / 50) % totalTicks;

    let accumulated = 0;
    for (const frame of animation.frames) {
      accumulated += frame.time;
      if (ticksElapsed < accumulated) {
        return frame.index;
      }
    }
    return animation.frames[animation.frames.length - 1].index;
  }
}
```

### WebGL Approach (for tint shaders)

For rainbow tinting, a fragment shader provides better performance:

```glsl
// Fragment shader for GTCEu rainbow tint
uniform sampler2D uTexture;
uniform float uTime;
uniform float uCycleTicks;

varying vec2 vTexCoord;

vec3 hsl2rgb(float h, float s, float l) {
  // HSL to RGB conversion
  ...
}

void main() {
  vec4 texColor = texture2D(uTexture, vTexCoord);

  // Calculate hue based on time
  float hue = mod(uTime / uCycleTicks, 1.0);
  vec3 tintColor = hsl2rgb(hue, 1.0, 0.5);

  // Apply tint (multiply blend)
  gl_FragColor = vec4(texColor.rgb * tintColor, texColor.a);
}
```

## Example Payloads

### Minimal Static Item
```json
{
  "id": "minecraft:stick",
  "type": "static",
  "size": 128,
  "data": "iVBORw0KGgo..."
}
```

### Simple Animation
```json
{
  "id": "minecraft:campfire",
  "type": "animated",
  "size": 128,
  "spriteSheet": "iVBORw0KGgo...",
  "animation": {
    "frameCount": 8,
    "frameHeight": 128,
    "frames": [
      {"index": 0, "time": 2},
      {"index": 1, "time": 2},
      {"index": 2, "time": 2},
      {"index": 3, "time": 2},
      {"index": 4, "time": 2},
      {"index": 5, "time": 2},
      {"index": 6, "time": 2},
      {"index": 7, "time": 2}
    ],
    "interpolate": false
  }
}
```

### Full GTCEu Machine
```json
{
  "id": "gtceu:max_input_bus",
  "type": "animated",
  "size": 128,
  "spriteSheet": "iVBORw0KGgo...",
  "layers": [
    {
      "name": "base",
      "region": {"x": 0, "y": 0, "width": 128, "height": 1280},
      "frameHeight": 128,
      "frameCount": 10,
      "animation": {
        "frames": [
          {"index": 0, "time": 60},
          {"index": 1, "time": 2},
          {"index": 2, "time": 2},
          {"index": 3, "time": 2},
          {"index": 2, "time": 2},
          {"index": 1, "time": 2},
          {"index": 0, "time": 60},
          {"index": 1, "time": 2},
          {"index": 2, "time": 2},
          {"index": 3, "time": 2},
          {"index": 4, "time": 2},
          {"index": 5, "time": 2},
          {"index": 6, "time": 2},
          {"index": 7, "time": 120},
          {"index": 6, "time": 2},
          {"index": 5, "time": 2},
          {"index": 4, "time": 2},
          {"index": 3, "time": 2},
          {"index": 2, "time": 2},
          {"index": 1, "time": 2}
        ],
        "interpolate": false
      }
    },
    {
      "name": "overlay",
      "region": {"x": 128, "y": 0, "width": 128, "height": 768},
      "frameHeight": 128,
      "frameCount": 6,
      "emissive": true,
      "animation": {
        "frames": [
          {"index": 0, "time": 2},
          {"index": 1, "time": 2},
          {"index": 2, "time": 2},
          {"index": 3, "time": 2},
          {"index": 4, "time": 2},
          {"index": 5, "time": 2}
        ],
        "interpolate": false
      }
    }
  ],
  "tint": {
    "type": "gtceu_rainbow",
    "cycleTicks": 288,
    "affectsLayers": ["base"]
  }
}
```

## Notes for Web Team

1. **Timing**: All `time` values are in Minecraft ticks (1 tick = 50ms)
2. **Frame indices**: 0-based, refer to position in sprite sheet
3. **Regions**: Coordinates in pixels within the combined sprite sheet
4. **Layer order**: Render in array order (first = bottom, last = top)
5. **Emissive**: Use additive blending or glow effect
6. **Interpolation**: If `interpolate: true`, blend between frames

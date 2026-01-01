# Icon Sync System

This directory contains tasks for implementing the new sprite-based icon sync system, replacing the previous GIF export approach.

## Overview

Instead of pre-rendering animated icons as GIF files (which are large and inflexible), the new system:

1. Exports raw sprite sheets + animation metadata
2. Uploads data with chunked transfer and hash verification
3. Lets the web application render animations client-side using WebGL/Canvas

## Tasks

| # | Task | Description | Status |
|---|------|-------------|--------|
| 01 | [Sync Command Structure](01-sync-command-structure.md) | Implement `/recipeflow sync all/icons/recipes` | Pending |
| 02 | [Sprite Sheet Export](02-sprite-sheet-export.md) | Build sprite sheet + metadata export system | Pending |
| 03 | [Chunked Upload](03-chunked-upload-verification.md) | Implement chunked upload with hash verification | Pending |
| 04 | [Web Payload Docs](04-web-payload-documentation.md) | Payload format documentation for web team | Pending |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Minecraft Mod                         │
├─────────────────────────────────────────────────────────────┤
│  /recipeflow sync icons                                      │
│       │                                                      │
│       ▼                                                      │
│  ┌─────────────────┐    ┌──────────────────────┐            │
│  │ IconSpriteExporter│──>│ SpriteSheetBuilder   │            │
│  └─────────────────┘    └──────────────────────┘            │
│       │                         │                            │
│       │                         ▼                            │
│       │                  ┌──────────────────────┐            │
│       │                  │AnimationMetadataBuilder│          │
│       │                  └──────────────────────┘            │
│       │                         │                            │
│       ▼                         ▼                            │
│  ┌─────────────────────────────────────────────┐            │
│  │            ChunkedIconUploader              │            │
│  │  - Compute SHA-256 hash                     │            │
│  │  - Split into 2MB chunks                    │            │
│  │  - Upload with retry logic                  │            │
│  │  - Verify server hash matches               │            │
│  └─────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                         Server API                           │
│  POST /upload/init → POST /upload/chunk → POST /upload/final │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       Web Application                        │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐                                       │
│  │   IconRenderer   │  Canvas 2D / WebGL                    │
│  │  - Load sprites  │                                       │
│  │  - Track time    │                                       │
│  │  - Composite     │                                       │
│  │  - Apply tints   │                                       │
│  └──────────────────┘                                       │
└─────────────────────────────────────────────────────────────┘
```

## Benefits Over GIF Approach

| Aspect | Old (GIF) | New (Sprites) |
|--------|-----------|---------------|
| File Size | 3.5MB for complex animations | ~50KB sprite sheet + JSON |
| Frame Count | 342 frames for MAX tier | 10 unique frames + timing data |
| Accuracy | GIF color palette limits | Full 32-bit color |
| Flexibility | Fixed playback | Pause, scrub, speed control |
| Compositing | Baked into GIF | Client-side layer compositing |
| Tinting | Pre-rendered per frame | Real-time shader tinting |

## Dependencies

### Existing Code to Reuse
- `SpriteAnimationMetadata.java` - Frame timing extraction
- `DirectFrameExtractor.java` - Extract frames from NativeImage
- `ObfuscationHelper.java` - Reflection utilities
- `GTCEuIconHelper.java` - GTCEu detection
- `SimpleRenderModeHelper.java` - GTCEu render mode

### Code to Remove
- GIF export methods in `IconExporter.java`
- Multiple rendering approaches in `AnimatedIconRenderer.java`
- Dead code already removed:
  - `AnimationCaptureScreen.java`
  - `AnimationCaptureProcessor.java`
  - `LiveAnimationCapture.java`
  - `TextureBasedAnimationExtractor.java`
  - `TextureLayerCompositor.java`
  - `TextureLayerExtractor.java`
  - `McmetaParser.java`

# RecipeFlow Mod - Architecture Documentation

**For Web App Team Integration**

This document describes the RecipeFlow Minecraft mod's architecture, API contracts, and data formats to enable proper integration with the web application.

---

## Table of Contents

1. [Overview](#overview)
2. [API Endpoints](#api-endpoints)
3. [Data Models](#data-models)
4. [Upload Protocols](#upload-protocols)
5. [Recipe Providers](#recipe-providers)
6. [Configuration](#configuration)
7. [User Commands](#user-commands)

---

## Overview

RecipeFlow is a Minecraft mod that extracts recipe data from modpacks and uploads it to the RecipeFlow API. It supports:

- **Vanilla Minecraft** recipes (crafting, smelting, smithing, etc.)
- **GregTech CEu Modern** machine recipes with full metadata
- **Any EMI-supported mod** via the EMI recipe viewer API

### Project Structure

```
recipeflow-mod/
├── core/                    # Java 8, version-agnostic code
│   └── src/main/java/
│       └── com.recipeflow.mod.core/
│           ├── api/         # RecipeData, ItemStackData, FluidStackData
│           ├── auth/        # AuthToken, AuthResult, DeviceFlowClient
│           ├── model/       # GregTechRecipeData, ItemMetadata, etc.
│           ├── export/      # HttpExporter, RecipeSerializer
│           ├── upload/      # ChunkedUploader, UploadResult
│           └── util/        # ManifestHasher, VersionDetector
│
└── forge-1.20.1/            # Minecraft 1.20.1 Forge implementation
    └── src/main/java/
        └── com.recipeflow.mod.v120plus/
            ├── auth/        # AuthStorage, AuthProvider
            ├── command/     # SyncCommand, AuthCommand
            ├── provider/    # GTCEuRecipeProvider, EMIRecipeProvider, etc.
            ├── util/        # ItemMetadataExtractor, IconUploader
            └── RecipeFlowMod.java
```

---

## API Endpoints

All endpoints use Bearer token authentication:
```
Authorization: Bearer {authToken}
```

### Recipe Sync

```
POST /api/modpacks/{slug}/versions/{version}/recipes/sync
Content-Type: application/json
Content-Encoding: gzip (optional)
```

**Request Body:**
```json
{
  "recipeCount": 12500,
  "manifestHash": "sha256:abc123...",
  "contentHash": "sha256:def456...",
  "recipes": [
    { "id": "...", "type": "...", "data": { ... } }
  ]
}
```

**Response:**
```json
{
  "success": true,
  "stats": {
    "received": 12500,
    "new": 1200,
    "updated": 300,
    "unchanged": 11000
  },
  "contentHash": "sha256:...",
  "version": "1.2.3"
}
```

### Chunked Upload (for large payloads)

See [Upload Protocols](#upload-protocols) section.

---

## Data Models

### Base: RecipeData

All recipes extend this base structure:

```json
{
  "id": "minecraft:iron_ingot_from_smelting",
  "type": "minecraft:smelting",
  "sourceMod": "minecraft"
}
```

### ItemStackData

```json
{
  "itemId": "minecraft:iron_ingot",
  "count": 1,
  "nbt": { }
}
```

### FluidStackData

```json
{
  "fluidId": "minecraft:water",
  "amount": 1000
}
```

Amount is in millibuckets (mB). 1000 mB = 1 bucket.

---

### Vanilla Recipes

**Type:** `minecraft:crafting_shaped`
```json
{
  "type": "minecraft:crafting_shaped",
  "pattern": ["###", "# #", "###"],
  "key": {
    "#": { "itemId": "minecraft:iron_ingot", "count": 1 }
  },
  "output": { "itemId": "minecraft:bucket", "count": 1 }
}
```

**Type:** `minecraft:crafting_shapeless`
```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    { "itemId": "minecraft:iron_ingot", "count": 1 },
    { "itemId": "minecraft:gold_ingot", "count": 1 }
  ],
  "output": { "itemId": "minecraft:alloy", "count": 1 }
}
```

**Type:** `minecraft:smelting` | `minecraft:blasting` | `minecraft:smoking` | `minecraft:campfire_cooking`
```json
{
  "type": "minecraft:smelting",
  "input": { "itemId": "minecraft:iron_ore", "count": 1 },
  "output": { "itemId": "minecraft:iron_ingot", "count": 1 },
  "experience": 0.7,
  "cookingTime": 200
}
```

**Type:** `minecraft:stonecutting`
```json
{
  "type": "minecraft:stonecutting",
  "input": { "itemId": "minecraft:stone", "count": 1 },
  "output": { "itemId": "minecraft:stone_bricks", "count": 1 }
}
```

**Type:** `minecraft:smithing_transform`
```json
{
  "type": "minecraft:smithing_transform",
  "template": { "itemId": "minecraft:netherite_upgrade_smithing_template", "count": 1 },
  "base": { "itemId": "minecraft:diamond_sword", "count": 1 },
  "addition": { "itemId": "minecraft:netherite_ingot", "count": 1 },
  "output": { "itemId": "minecraft:netherite_sword", "count": 1 }
}
```

---

### GregTech Machine Recipes

**Type:** `gregtech:machine`

```json
{
  "type": "gregtech:machine",
  "machineType": "chemical_reactor",
  "voltageTier": "HV",
  "euPerTick": 480,
  "duration": 200,
  "circuit": 3,
  "inputs": {
    "items": [
      { "itemId": "gtceu:sodium_dust", "count": 1 }
    ],
    "fluids": [
      { "fluidId": "minecraft:water", "amount": 1000 }
    ]
  },
  "outputs": {
    "items": [
      {
        "itemId": "gtceu:sodium_hydroxide_dust",
        "count": 1,
        "chance": 0.75,
        "boostPerTier": 0.05
      }
    ],
    "fluids": [
      { "fluidId": "gtceu:hydrogen", "amount": 500 }
    ]
  },
  "specialConditions": {
    "cleanroom": true,
    "vacuum": false,
    "coilTier": 2
  }
}
```

**Voltage Tiers:**

| Tier | EU/t |
|------|------|
| ULV | 8 |
| LV | 32 |
| MV | 128 |
| HV | 512 |
| EV | 2048 |
| IV | 8192 |
| LuV | 32768 |
| ZPM | 131072 |
| UV | 524288 |
| UHV | 2097152 |

**Circuit Field:**
- Integer 1-32, or `null` if no programmed circuit required
- Many GT recipes share identical inputs/outputs but differ only by circuit

**Chanced Outputs:**
- `chance`: 0.0-1.0, null = 100%
- `boostPerTier`: Chance increase per voltage tier above recipe minimum

**Special Conditions:**
- `cleanroom`: Requires cleanroom multiblock
- `vacuum`: Requires vacuum environment
- `coilTier`: Minimum coil tier for EBF recipes
- Additional conditions stored as key-value pairs

---

### Generic Recipes (Other Mods)

For mods without dedicated providers, recipes use a flexible format:

```json
{
  "type": "create:mixing",
  "machineType": "mechanical_mixer",
  "energy": 0,
  "duration": 100,
  "inputs": {
    "items": [...],
    "fluids": [...]
  },
  "outputs": {
    "items": [...],
    "fluids": [...]
  },
  "conditions": {
    "heated": true
  }
}
```

---

### Item Metadata

Uploaded separately via chunked protocol:

```json
{
  "items": [
    {
      "itemId": "gtceu:sodium_hydroxide_dust",
      "displayName": "Sodium Hydroxide Dust",
      "tooltipLines": [
        "Chemical Formula: NaOH",
        "Melting Point: 596K"
      ]
    },
    {
      "itemId": "minecraft:iron_ingot",
      "displayName": "Iron Ingot",
      "tooltipLines": []
    }
  ]
}
```

- First tooltip line (display name duplicate) is excluded
- Tooltips capture mod-specific information (GT formulas, etc.)

---

### Icon Metadata

Uploaded as a ZIP with metadata JSON:

```json
{
  "minecraft:iron_ingot": {
    "filename": "minecraft_iron_ingot.png",
    "animated": false,
    "frameCount": 1,
    "frameTimeMs": 0
  },
  "minecraft:lava_bucket": {
    "filename": "minecraft_lava_bucket.webp",
    "animated": true,
    "frameCount": 16,
    "frameTimeMs": 50
  }
}
```

**ZIP Structure:**
```
icons.zip
├── icon-metadata.json
├── minecraft_iron_ingot.png
├── minecraft_lava_bucket.webp
├── gtceu_sodium_hydroxide_dust.png
└── ...
```

**Naming Convention:** `{namespace}_{path}.png` (colons replaced with underscores)

---

## Upload Protocols

### Simple Upload (Recipes)

Single POST request with optional GZIP compression. Used for recipe sync where payload is typically 1-10MB compressed.

### Chunked Upload (Icons, Item Metadata)

For large payloads (50-200MB for icons), uses a 4-step protocol:

#### Step 1: Start Session

```
POST /api/modpacks/{slug}/versions/{version}/upload/start
Content-Type: application/json
```

```json
{
  "type": "icons",
  "totalSize": 52428800,
  "chunkSize": 5242880
}
```

**Response:**
```json
{
  "sessionId": "abc123-def456"
}
```

#### Step 2: Check Status (for resume)

```
GET /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/status
```

**Response:**
```json
{
  "uploadedChunks": [0, 1, 2, 5]
}
```

Skip chunks already uploaded when resuming.

#### Step 3: Upload Chunks

```
POST /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/chunk/{index}
Content-Type: application/octet-stream
X-Chunk-Hash: sha256:abc123...
```

Body: Raw binary chunk data (optionally GZIP compressed)

#### Step 4: Complete Upload

```
POST /api/modpacks/{slug}/versions/{version}/upload/{sessionId}/complete
Content-Type: application/json
```

```json
{
  "contentHash": "sha256:..."
}
```

**Chunk Sizes:**

| Type | Chunk Size | Rationale |
|------|------------|-----------|
| recipes | 5 MB | Text compresses well |
| items | 2 MB | Smaller payload |
| icons | 5 MB | Balance progress vs overhead |

---

## Recipe Providers

The mod uses a priority-based provider system. Higher priority providers are processed first, and the first provider to return a recipe "wins" (deduplication by recipe ID).

| Provider | Priority | Mod Required | Description |
|----------|----------|--------------|-------------|
| GTCEuRecipeProvider | 100 | gtceu | Direct GTCEu API access, full machine recipe metadata |
| EMIRecipeProvider | 50 | emi | EMI recipe viewer integration, supports any EMI-compatible mod |
| JEIRecipeProvider | 50 | jei | JEI integration (framework exists) |
| VanillaRecipeProvider | 10 | None | Direct RecipeManager access, all vanilla types |

**Extraction Order:**
1. GTCEu recipes (if loaded) - highest accuracy for GT data
2. EMI/JEI recipes - covers most modded recipes
3. Vanilla recipes - fallback for anything missed

---

## Configuration

**File:** `config/recipeflow-common.toml`

```toml
[server]
# Required - API server URL
url = "https://api.recipeflow.io"

# Required - Authentication token
authToken = "your-auth-token"

# Required - Modpack slug identifier
modpackSlug = "gtnh-modern"

[sync]
# Recipes per batch (100-10000)
batchSize = 1000

# HTTP timeout in milliseconds (5000-300000)
timeoutMs = 30000

# Enable GZIP compression for uploads
compression = true

[advanced]
# Enable debug logging
debug = false

# Override auto-detected modpack version
versionOverride = ""
```

---

## User Commands

All commands require OP level 2.

### `/recipeflow sync`

Main command to extract and upload all recipe data.

**Execution Flow:**
1. Detect modpack version (from `manifest.json` or `pack.toml`)
2. Compute manifest hash (SHA-256 of sorted mod list)
3. Extract recipes from all providers
4. Export icons to `config/recipeflow/icons/` (client-side)
5. Extract item metadata (display names, tooltips)
6. Upload icons via chunked protocol
7. Upload item metadata via chunked protocol
8. Upload recipes with manifest hash

**Output:**
```
[RecipeFlow] Starting sync...
[RecipeFlow] Detected version: 2.7.1 (CurseForge)
[RecipeFlow] Manifest hash: sha256:abc123... (147 mods)
[RecipeFlow] Extracted 12,547 recipes from 4 providers
[RecipeFlow] Exported 3,241 icons
[RecipeFlow] Uploading icons... 100%
[RecipeFlow] Uploading item metadata... 100%
[RecipeFlow] Sync complete: 1,200 new, 300 updated, 11,047 unchanged
```

### `/recipeflow login`

Authenticate using OAuth 2.0 Device Flow (Discord login).

**Flow:**
1. Mod requests device code from API
2. Displays clickable link in chat
3. User opens browser, logs in via Discord
4. Mod polls until authentication completes
5. Token stored in `config/recipeflow-auth.json`

**Output:**
```
[RecipeFlow] Requesting login code...
[RecipeFlow] [Click here to login]
[RecipeFlow] Or visit https://recipeflow.io/device and enter code: ABCD-1234
[RecipeFlow] Waiting for authorization... (expires in 15 minutes)
[RecipeFlow] Successfully logged in!
```

### `/recipeflow logout`

Clear stored device flow authentication.

### `/recipeflow status`

Shows current configuration, auth status, and provider status.

### `/recipeflow help`

Lists available commands and config file location.

---

## Authentication

The mod supports two authentication methods:

### 1. Device Flow (Recommended)

Use `/recipeflow login` for seamless browser-based authentication via Discord.

- Token stored in `config/recipeflow-auth.json`
- Automatically used by `/recipeflow sync`
- Token expires after 7 days (re-run `/recipeflow login`)

### 2. Config File Token

Set `authToken` in `config/recipeflow-common.toml`:

```toml
[server]
authToken = "your-token-here"
```

**Priority:** Device flow token takes precedence over config file token.

---

## Manifest Hash

The manifest hash provides modpack verification:

**Purpose:**
- Detect modified packs vs official CurseForge/Modrinth versions
- Prevent modified packs from overwriting verified recipe data
- Ensure community flowcharts work for users on official packs

**Computation:**
1. Read `manifest.json` (CurseForge) or `pack.toml` (Modrinth)
2. Extract mod identifiers (`projectID:fileID` or `file:hash`)
3. Sort alphabetically
4. Compute SHA-256 hash
5. Return with `sha256:` prefix

**Same mod list = same hash** (deterministic)

---

## Version Detection

The mod auto-detects modpack version from:

1. **CurseForge:** `manifest.json` → `version` field
2. **Modrinth:** `pack.toml` → `version = "..."` line
3. **Config override:** `versionOverride` setting (takes precedence)

---

## Error Handling

The mod provides user-friendly error messages:

| Error | Message |
|-------|---------|
| Network timeout | "Connection timed out. Check your network and server URL." |
| DNS failure | "Could not resolve server hostname. Check the server URL." |
| Connection refused | "Could not connect to server. Is it running?" |
| 401 Unauthorized | "Server returned 401: Invalid or expired auth token" |
| 404 Not Found | "Server returned 404: Modpack or version not found" |

---

## Appendix: Type Reference

### Recipe Types

| Type String | Source |
|-------------|--------|
| `minecraft:crafting_shaped` | Vanilla |
| `minecraft:crafting_shapeless` | Vanilla |
| `minecraft:smelting` | Vanilla |
| `minecraft:blasting` | Vanilla |
| `minecraft:smoking` | Vanilla |
| `minecraft:campfire_cooking` | Vanilla |
| `minecraft:stonecutting` | Vanilla |
| `minecraft:smithing_transform` | Vanilla |
| `minecraft:smithing_trim` | Vanilla |
| `gregtech:machine` | GTCEu |
| `{modid}:{type}` | Other mods |

### Machine Types (GTCEu)

Common machine types include:
- `chemical_reactor`
- `electrolyzer`
- `centrifuge`
- `macerator`
- `electric_blast_furnace`
- `assembler`
- `chemical_bath`
- `mixer`
- `distillery`
- `extractor`
- ... (hundreds more)

---

*Document generated for RecipeFlow Mod v1.0.0*

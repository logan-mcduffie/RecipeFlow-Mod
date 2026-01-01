# Task: Implement /recipeflow sync Command Structure

## Overview

Restructure the `/recipeflow sync` command to support granular sync operations, allowing users to sync only what they need.

## Command Structure

```
/recipeflow sync all      - Full sync (icons + recipes + metadata)
/recipeflow sync icons    - Items, blocks, fluids sprite data only
/recipeflow sync recipes  - Recipe logic only (faster for modpack updates)
```

## Rationale

Modpack updates often only change recipes (balance tweaks, new crafting paths) without adding new items/blocks. In these cases, re-exporting and uploading all icons is wasteful:
- Icon export is slow (requires rendering thousands of items)
- Icon upload is large (sprite sheets + metadata)
- Recipe-only updates are common and should be fast

## Implementation Details

### Command Registration

Update `SyncCommand.java` to register subcommands:

```java
Commands.literal("sync")
    .then(Commands.literal("all")
        .executes(ctx -> executeSync(ctx, SyncMode.ALL)))
    .then(Commands.literal("icons")
        .executes(ctx -> executeSync(ctx, SyncMode.ICONS)))
    .then(Commands.literal("recipes")
        .executes(ctx -> executeSync(ctx, SyncMode.RECIPES)))
    .executes(ctx -> executeSync(ctx, SyncMode.ALL)) // default
```

### SyncMode Enum

```java
public enum SyncMode {
    ALL,      // Icons + Recipes + Item Metadata
    ICONS,    // Sprite sheets + animation metadata only
    RECIPES   // Recipe data only (fastest)
}
```

### Execution Flow

**ICONS mode:**
1. Export sprite sheets for all items/blocks/fluids
2. Export animation metadata (frame timing, layers, tints)
3. Upload with chunked transfer + hash verification
4. Skip recipe extraction entirely

**RECIPES mode:**
1. Extract recipes from all providers
2. Extract item metadata (names, tooltips, tags)
3. Upload recipe JSON
4. Skip icon export entirely

**ALL mode:**
1. Check if icons already exist on server (hash check)
2. If icons missing/changed: run ICONS flow
3. Run RECIPES flow

## Files to Modify

- `command/SyncCommand.java` - Add subcommand structure
- Create `sync/SyncMode.java` enum
- Update help text in `executeHelp()`

## Acceptance Criteria

- [ ] `/recipeflow sync all` performs full sync
- [ ] `/recipeflow sync icons` only exports/uploads icon data
- [ ] `/recipeflow sync recipes` only exports/uploads recipe data
- [ ] `/recipeflow sync` (no subcommand) defaults to `all`
- [ ] Help text documents all sync modes
- [ ] Progress messages indicate which mode is running

# RecipeFlow Companion Mod

Forge mod for extracting recipes from Minecraft modpacks and syncing them to the RecipeFlow web application.

## Features

- Recipe extraction from loaded mods (vanilla, GregTech, and others via EMI/JEI)
- Priority-based provider system (GTCEu API > EMI/JEI > Vanilla fallback)
- JSON serialization matching the RecipeFlow API contract
- Multi-version support: 1.7.10, 1.12.2, 1.19+ (starting with 1.20.1)

## Project Structure

```
recipeflow-mod/
├── core/           # Shared code (Java 8) - data models, serialization
├── forge-1.19/     # 1.19+ mod (Java 17) - EMI/JEI/GTCEu providers
├── docs/           # API documentation
└── tasks/          # Task tracking
```

## Building

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :core:build
./gradlew :forge-1.19:build
```

## Development

### Prerequisites

- JDK 17+ (for forge-1.19 module)
- JDK 8+ (for core module)
- Gradle 8.x

### Setup

1. Clone the repository
2. Run `./gradlew build` to download dependencies
3. Import into your IDE as a Gradle project

## Configuration

After installing, configure in `config/recipeflow-common.toml`:

```toml
[server]
url = "https://your-recipeflow-server.com"
authToken = "your-auth-token"
modpackSlug = "your-modpack-slug"
```

## Usage

In-game, run:
```
/recipeflow sync
```

## Architecture

See [tasks/ARCHITECTURE.md](tasks/ARCHITECTURE.md) for detailed architecture documentation.

## API References

- [GTCEu Modern API](docs/GTCEU-MODERN-API.md) - GregTech CEu Modern recipe extraction

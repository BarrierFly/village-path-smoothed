# AGENTS.md

Development conventions for AI agents (and humans) working on this repository.

## Project in one sentence

Rewrite vanilla village road generation at world-creation time into slab-stepped
roads (max half-block drop, forward and lateral), plus kerbs, leaf railings and
scaffolding water crossings. Fabric mod, server-side worldgen only.

## Layout & layering

```
src/main/java/dev/peoplecanfly/village_path_smoothed/
├── VillagePathSmoothed(.java)      # entrypoint: logging + config load only
├── VillagePathSmoothedConfig.java  # Gson POJO config, no external library
├── mixin/       # wiring ONLY - no algorithms here
│   ├── MixinCreateWorldScreen           # client-only: enabled toggle on the create-world screen
│   ├── MixinStructureStart              # the single worldgen hook (placeInChunk RETURN)
│   ├── MixinSinglePoolElement           # @Accessor("template")
│   ├── MixinPoolElementStructurePiece   # @Accessor("structureTemplateManager")
│   ├── MixinStructureTemplate           # @Accessor("palettes")
│   └── MixinCommands                    # registers /vps (no fabric-api dependency)
├── duck/        # interfaces implemented by the mixins
├── pipeline/    # orchestration + pure algorithm
│   ├── VpsPipeline          # entry from the mixin: gate -> solve (cached) -> write
│   ├── RoadCollector        # templates -> road mask (MC-touching)
│   ├── RoadNetwork          # mask -> arms/regions decomposition (pure)
│   ├── RoadSmoother         # arm slice DP + region ICM (pure, unit tested)
│   ├── RoadMaterialTable    # style -> blocks (MC-touching)
│   └── RoadWriter           # profile -> blocks, kerbs, railings, scaffolds
├── terrain/     # height probing: real heightmap (+/-1 chunks) vs noise
├── compat/      # VpsCompat: version shims; modmenu/: optional client screen
└── command/     # /vps brigadier tree
```

Rules:
- **mixin classes never contain algorithms**; they capture context and call
  `VpsPipeline`.
- **`RoadSmoother`, `RoadNetwork`, `RoadCell` never import Minecraft** - they
  must stay JUnit-testable (`src/test/java/.../RoadSmootherTest.java`).
- Layering is one-way: mixin -> pipeline -> terrain -> (Minecraft).

## Multi-version rules

- Targets: 1.19.4, 1.20.1, 1.21.1, 1.21.10, 1.21.11 (main), 26.3, built from
  one source tree with the ReplayMod preprocessor (`//#if MC >= ...`).
- Always use official Mojang mappings (mojmap) names.
- Version differences currently handled by the preprocessor:
  - `ResourceLocation` (<= 1.21.10) vs `Identifier` (>= 1.21.11): see
    `VpsCompat` / `MixinSinglePoolElement`;
  - `ResourceKey#location()` vs `#identifier()`;
  - `RegistryAccess#registryOrThrow` (1.19.4) vs `#lookupOrThrow`.
- `SinglePoolElement#getTemplateLocation()` does not exist on 1.19.4 - always
  go through the `@Accessor("template")` mixin (the field name is stable).
- 26.3 builds unobfuscated through `fabric-loom` (no remap) with Java 25;
  older nodes use `fabric-loom-remap` (17 or 21). Version facts live in
  `versions/<mc>/gradle.properties`.

## Hard constraints (easy to get wrong)

- Structure placement is **incremental per chunk**: `placeInChunk` only sees and
  writes the current chunk's 16x16 writable area. Cells outside it are written
  when their own chunk generates. Never try to write the whole village at once.
- Reading heights is only reliable within +/-1 chunks of the current chunk
  (`WorldGenRegion`); beyond that use the noise base height
  (`ChunkGenerator#getBaseHeight`). The solved profile is cached on the
  `StructureStart` (duck interface `StructureStartVpsAccess`) so the village is
  solved exactly once.
- Write blocks with `Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE` (vanilla
  structure flags): no client updates during worldgen and heightmaps stay
  untouched (so our own probes stay consistent).
- `ScaffoldingBlock` re-computes `distance` on its first tick; unsupported
  spans decay. That is a documented known issue - do **not** "fix" it silently.
- Kerbs replace only `#minecraft:dirt` (plus configured ids). Do not widen the
  set silently; sand/snow/dirt_path are excluded on purpose (README documents
  it and the config can extend it).
- Slabs are always **bottom** slabs; no stairs, no orientation states - road
  placement must stay rotation-independent.
- Water-crossing cells are fixed anchors at the water surface; the solver may
  never move them.
- `/vps` is registered by mixin into `Commands#<init>` because the mod
  deliberately does not depend on fabric-api.
- Client mixins (CreateWorldScreen toggle) live in the mixin config's
  **"client"** section and must never be listed under "mixins" - the
  CreateWorldScreen class does not exist on a dedicated server.
- The per-world enabled switch is a JSON file in the world folder:
  `MinecraftServer#getWorldPath(LevelResource.ROOT)` + `village_path_smoothed.json`
  (this API is identical in all six supported versions, unlike SavedData whose
  API changed four times). It is the AUTHORITATIVE gate; the global config
  `enabled` only seeds worlds without their own file. The create-world-screen
  toggle binds to the world being created: MixinCreateWorldScreen arms
  `VpsWorldSettings#armForCreation(uiState.getName())` in `onCreate` and the
  armed choice is consumed once at that world's first chunk generation
  (matched by `ServerLevelData#getLevelName()`). The per-server flag is cached
  in a WeakHashMap - do not read the file per chunk.

## Code style

- Tab indentation, English comments, English identifiers.
- One WTFPL header comment per file (no license Gradle plugin - it was removed
  on purpose, see Q26 of the plan).
- Keep the `pipeline` pure core free of Minecraft imports.

## Build & test

```
./gradlew :1.21.11:test        # pure algorithm unit tests
./gradlew :1.21.11:build       # single version
./gradlew buildAndGather       # all versions, jars collected in build/libs
./gradlew :1.21.11:runServer   # smoke test (needs run/eula.txt)
```

## Do not

- Do not commit `run/`, `build/`, `.gradle/`, planning documents, or dumps.
- Do not add dependencies without agreement (the mod has none besides
  compile-only ModMenu).
- Do not process existing chunks at runtime; world creation only.

## References

- Plan of record: workspaces's `村路平滑化-细化规划.md` (kept OUT of this repo).
- Vanilla reference sources: workspace `guardian` repo - branch
  `origin/parchment/vineflower` = 1.21.11 (mojmap), commit `d5822ca2a1` on
  `origin/mojmap/vineflower` = 26.3, `ddf752f9` = 1.19.4-pre2.
- Key vanilla facts: village street template pools under
  `data/minecraft/worldgen/template_pool/village/<style>/streets.json`;
  `GravityProcessor` = terrain matching; `#minecraft:dirt` = 10 blocks;
  village structure tag = the five `village_*` ids.

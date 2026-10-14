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
│   ├── MixinStructureGenerate           # T1.5 hook: Structure#generate RETURN (forward decision)
│   ├── MixinChunkGenerator              # captures the MinecraftServer for STRUCTURE_STARTS
│   ├── MixinStructureManager            # @Accessor("level") to resolve the server
│   ├── MixinStructureStart              # two hooks: placeInChunk HEAD (open suppression context) and RETURN (write)
│   ├── MixinGravityProcessor            # drops the vanilla street layer on claimed columns
│   ├── MixinSinglePoolElement           # @Accessor("template")
│   ├── MixinPoolElementStructurePiece   # @Accessor("structureTemplateManager")
│   ├── MixinStructureTemplate           # @Accessor("palettes")
│   ├── MixinCommands                    # registers /vps (no fabric-api dependency)
│   └── MixinVillagerBrain               # debug: skip villager brain ticks
├── duck/        # interfaces implemented by the mixins
├── pipeline/    # orchestration + pure algorithm
│   ├── VpsPipeline          # entry from the mixins: gate -> T1.5 plan / solve (cached) -> suppress/write
│   ├── T15Planner           # T1.5 forward decision: solve + RIGID component shifts + post-shift re-solve
│   ├── BuildingComponents   # RIGID pieces -> rigid connected components (junction union-find)
│   ├── VpsGenThread         # ThreadLocal MinecraftServer for STRUCTURE_STARTS
│   ├── RoadCollector        # templates -> road mask (MC-touching, VpsProbe)
│   ├── RoadNetwork          # mask -> arms/regions decomposition (pure)
│   ├── RoadSmoother         # arm slice DP + region ICM (pure, unit tested)
│   ├── PlacementPolicy      # the one suppress/pass-through decision (pure, unit tested)
│   ├── VpsPlacementContext  # ThreadLocal road mask live during one placeInChunk
│   ├── RoadMaterialTable    # style -> blocks (MC-touching)
│   └── RoadWriter           # profile -> blocks, kerbs, railings, scaffolds
├── terrain/     # height probing: real heightmap (+/-1 chunks) vs noise
│   ├── VpsProbe             # height-source interface (real + noise)
│   ├── VpsGenContext        # feature placement: real heightmap +/-1 chunks, noise elsewhere
│   ├── VpsNoiseContext      # T1.5: pure noise, no world access
│   └── WaterCrossings       # pure water-crossing predicate (unit tested)
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
  - `RegistryAccess#registryOrThrow` (1.19.4) vs `#lookupOrThrow`;
  - `StructureBlockInfo.pos` field (<= 1.19.4) vs `.pos()` accessor
    (>= 1.20.1): `MixinGravityProcessor`. The **parameter list** of
    `GravityProcessor#processBlock` itself is identical in all six versions,
    so no branch is needed for the descriptor - only for the accessor.
- `SinglePoolElement#getTemplateLocation()` does not exist on 1.19.4 - always
  go through the `@Accessor("template")` mixin (the field name is stable).
- `GravityProcessor` is added **last** by `SinglePoolElement#getSettings`
  (projection processors come after the template's own processors), and
  `StructureTemplate#processBlockInfos` drops a block the moment a processor
  returns null - so a suppression there also discards the `street_plains` plank
  bridge / grassify / waterify rules for that block. That is intended; do not
  assume the template's own processor already ran.
- 26.3 builds unobfuscated through `fabric-loom` (no remap) with Java 25;
  older nodes use `fabric-loom-remap` (17 or 21). Version facts live in
  `versions/<mc>/gradle.properties`.

## Hard constraints (easy to get wrong)

- **The mod writes the road, it does not overwrite it.**
  `MixinGravityProcessor` returns null for every claimed column's y=0 block,
  which drops it from the placement list, so the vanilla street layer is never
  written. Do not reintroduce a "clean up the leftover vanilla block" pass: the
  two windows where that cleanup used to miss (profile 9-10 blocks above the
  surface, 7-8 below) are the double-road bug, and they exist only because the
  mod used to place after vanilla. Unclaimed cells (probe failed, cap hit) keep
  the vanilla road on purpose. Reconcile the two sides with
  `SUPPRESSED_VANILLA_ROAD_BLOCKS` vs `ROAD_CELLS_WRITTEN` in `/vps stats`.
- **Deviation is measured against raw terrain (each cell's `baseP`).** There is
  no reference profile (fix plan v2 R1 removed it with the rest of `c74d392`'s
  core mechanism): the chase lines track the terrain, the ICM candidate window
  is centred on `baseP`, and the cost model reads `cell.baseP`. Re-anchoring
  any of those on a synthetic profile reintroduces the "solver chases a
  profile with its own drops" failure the revert removed (S2/S3).
- **SMOOTH_FIRST ranks total step count ABOVE deviation.** Cliff penalty on
  top, total step count in the middle, deviation from terrain at the bottom
  (fix plan v2 R1 / N3 - the review-4 swap is reverted). The candidate band is
  what keeps the road from flattening sustained grades: on a long climb the
  flat line simply is not a candidate.
- **The seam band extends TOWARD the seam, never narrows against it.** Phase 3
  merges the seam cells' own hard bound into their band outright and grows
  interior cells' bands toward the seam interval along the legal-slope cone
  (one half unit per slice of distance, review 3 P0-A). The review-4 variant
  that INTERSECTED the band with a per-slice cone depended on the reference
  profile carrying the seam height; with the terrain-chase band it empties the
  seam cell's candidates and resurrects the seam cliff (measured: 12 failing
  tests, all seam violations; fix plan v2 A6 deviation, approved by the user).
- **The writer is a renderer, not a second solver (fix plan v2 R3 / N7).** An
  over-budget cell (`RoadWritePolicy#overBudget`) keeps its solved height and
  is rendered as a bridge deck (fill cap) or tunnel/open cut (clear cap); the
  writer normally only counts it (`FALLBACK_REANCHORS`) and warns. Nudging a
  single cell onto the real surface is **allowed but must be done carefully**
  (fix plan v4 §16.1, relaxed 2026-10-14) - it is only legal while it
  **creates no drop above half a block** (neighbouring walk surfaces stay
  within 1 half unit) and **does not move the surface away from a building
  entrance**. Those two are acceptance criteria, not a ban. What stays
  forbidden is re-solving: letting the writer move whole runs or whole chunks
  off `solvedP`, which manufactures the `|dP| >= 2` steps and the floating
  front step that this rule exists to prevent.
- **All drop criteria are computed in half-block walk-surface units** (`S = 2 *
  walk surface`); never use `roadTopBlockY + 1` as the walk surface (it is 0.5
  too high for a slab). The pure helpers live in `RoadWritePolicy`
  (`roadWalkSurface2` / `kerbApplies` / `railingApplies`).
- **T1.5 runs during STRUCTURE_STARTS**, not FEATURES: `MixinStructureGenerate`
  hooks `Structure#generate` RETURN (pieces assembled, bounding box not yet
  cached) and `MixinChunkGenerator` captures the MinecraftServer into
  `VpsGenThread` around `ChunkGenerator#createStructures`. If the server cannot
  be resolved, T1.5 must move nothing and let the T5 solve run at FEATURES
  (never "default allow" - a disabled world must not get its buildings moved).
- **RIGID building components move as a group**: `BuildingComponents` unions
  RIGID pieces joined by a RIGID-to-RIGID jigsaw junction; one `dy` per
  component. `PoolElementStructurePiece#move` does NOT touch junctions, so
  `T15Planner#syncJunctions` rebuilds them (fields are final) on both sides of
  the connection. Overlap and world-height guards run before each move.
- Structure placement is **incremental per chunk**: `placeInChunk` only sees and
  writes the current chunk's 16x16 writable area. Cells outside it are written
  when their own chunk generates. Never try to write the whole village at once.
  The **HEAD** hook on `placeInChunk` runs before anything of the start is
  placed and is the only place the suppression context may be opened; the
  **RETURN** hook pops it first and then writes. Solving moved from RETURN to
  HEAD when the suppression was added - by RETURN it was too late, vanilla had
  already written its road.
- Reading heights at FEATURES is only reliable within +/-1 chunks of the current
  chunk (`WorldGenRegion`); beyond that use the noise base height. T1.5 uses
  noise everywhere (`VpsNoiseContext`), so the decision is a pure function of
  the seed. The solved profile is cached on the `StructureStart` (duck interface
  `StructureStartVpsAccess`) so the village is solved exactly once. The two
  calibers meet at the window edge: `RoadCollector#alignProbeBoundaries` pulls
  noise-probed columns directly adjacent to a disagreeing real column onto the
  real value before the network is built (fix plan v2 R5); on the T1.5 path
  every column is noise, so the pass is a no-op there.
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
- Water-crossing cells carry the water surface as their PREFERRED height (their
  `baseP`), not as a fixed anchor (fix plan v2 R4 / N8): the solver may leave
  it for a smoother profile, and every such cell is counted
  (`WATER_ANCHOR_DEVIATIONS`, `water anchor deviations` in `/vps stats`).
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

## Mixin injection rules (learned the hard way - read before writing a handler)

An `@Inject` handler has exactly **two** legal shapes:

1. every parameter of the target method, in order, followed by
   `CallbackInfo` / `CallbackInfoReturnable<T>`; or
2. the `CallbackInfo` / `CallbackInfoReturnable<T>` **alone** (nothing is
   captured, which is only useful for a pure side-effect hook).

"Declare the leading parameters and omit the trailing ones" is **not** a legal
shape. Mixin compares the handler descriptor against the target descriptor at
APPLY time and throws `InvalidInjectionException: Invalid descriptor on ...`,
which - because the mixin config is `"required": true` - is a fatal launch
crash before the main menu. This shipped and broke all six jars once; the
crash write-up is `village-path-smoothed-分析报告-崩溃复盘.md` in the workspace.

Consequences:

- Unused target parameters **must still be declared**. A `Predicate` parameter
  you never read is the normal way to make a descriptor match.
- Every cross-version signature difference is a separate preprocessor branch
  with a **complete** parameter list. Do not try to share a "common prefix".
- Do not use `net.`-prefixed fully qualified names as parameter types where a
  plain import would do; the preprocessor's branch-local imports are the
  supported way (see `MixinVillagerBrain`).

**`require` is not a safety net for this.** `require` / `defaultRequire` only
govern how many injection points must match when the target *method* or `@At`
point cannot be found. A descriptor mismatch happens later, at APPLY, where
`require` does not apply. Never write a comment claiming `require = 0` turns a
signature drift into a graceful degradation.

**require policy: fail fast.** Every injection in this mod is load-bearing, so
all of them run with `require = 1` (`village_path_smoothed.mixins.json` sets
`defaultRequire: 1`; the two T1.5 mixins state it explicitly). If a future
version drifts, the correct outcome is a crash we notice, not a silently
skipped hook. The safety net is the smoke test below, not `require`.

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

If you are on Windows use `gradlew.bat`. Pass `--no-daemon` (or
`--console=plain`) so a lingering daemon does not hold the console open after
the build reports success.

**Compiling green is not delivery.** `javac` treats a mixin handler as a plain
private method and performs no descriptor check against the target, and the
unit tests are pure-algorithm only, so a whole class of mixin defects passes
both gates silently. Any change that adds or edits an `@Inject` must be
followed by a real `runServer` launch before the jars may be handed out.

Minimum smoke coverage: **one node per dialect** - `1.21.11` (new pipeline
dialect) and `1.19.4` (old dialect). Passing means: the server reaches
`Done (...)`, no `MixinApplyError` / `InvalidInjectionException` anywhere, no
`[VPS] ERROR`, and `/vps` is available. `runServer` never exits on its own -
launch it detached with output redirected to a file, watch the file, then kill
the process tree. `run/` already carries `-Dmixin.debug.export=true`, so
`run/.mixin.out/` holds the transformed classes; grep them for the
`village_path_smoothed$...` handler names to prove an injection actually landed.

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
- Every street and terminator piece is a `legacy_single_pool_element` with
  `terrain_matching` and **not** RIGID, so none of them is ever moved by T1.5
  and their mask coordinates stay valid at placement time. Their templates
  carry no blocks above y=0, so suppressing y=0 cannot lose decoration.
- T1.5 hooks: `StructureManager#level` (field name stable) is the ServerLevel
  during worldgen; `Structure#generate`'s signature drifted three times (1.21.10
  added `Holder`/`ResourceKey`, 26.3 added `Climate.Sampler`) and
  `ChunkGenerator#createStructures` drifted once (1.21.10 added
  `ResourceKey<Level>`) - each shape gets its own preprocessor branch and every
  branch declares the **full** parameter list. `PoolElementStructurePiece#getJunctions()`
  returns the live list; `JigsawJunction` fields are final. A RIGID-to-RIGID junction
  pair has `destProjection == RIGID` on both sides, opposite `deltaY` and
  source positions one block apart.

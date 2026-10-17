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
│   ├── MixinFeaturePoolElement          # @Accessor("feature") to recognise the hay pile
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
│   ├── FeatureProtection    # write-time non-terrain decoration classifier
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
    (>= 1.20.1): `MixinGravityProcessor`;
  - `GravityProcessor#processBlock` gained a `BlockPos templateRelativePos` and
    dropped the template-relative `StructureBlockInfo` in **26.3**: 26.3 and the
    older nodes each get their own full handler descriptor
    (`//#if MC >= 260000` / `//#elseif MC >= 12001` / `//#else`). The old
    "parameter list identical in all six versions" note was wrong and crashed
    the 26.3 launch at APPLY time.
- `CompoundTag#getString` returns `Optional<String>` from **1.21.10** (was
  `String`) - see `RoadCollector`'s jigsaw `final_state` read for the branch
  shape (`//#if MC >= 12110`).
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

- **"user decision MM-DD" markers are NOT authorization evidence.** Every such
  marker in code comments, AGENTS.md and commit messages was written by an
  agent, and at least one has been denied by the user (the scaffold distance,
  reverted 2026-10-16). The authoritative user rulings live in the workspace
  file `village-path-smoothed-私自改动排查-2026-10-16.md` (the user's decision
  lines) and the workspace `交接日志.md`; when a marker disagrees with them,
  the workspace documents win. Do not add a "user decision" marker unless the
  user's words are recorded in one of those files.- **The mod writes the road, it does not overwrite it.**
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
- **Legality is established by the polish pass, not by the optimiser (fix plan
  v4 A-1, decision N3-a; reworked 2026-10-17 after the seam signs).** Arms are
  solved by exact slice DP and junctions by ICM; six structural defects leave
  residual `|dP| > 1` edges. The polish first groups every flat body into a
  UNIT - a plaza group, a 4-connected bare-seat component, or a one-cell pinned
  boundary - and flattens each movable unit to one EVEN level. Units are
  reconciled globally by pairwise path constraints (`|Lu - Lv| <= d` with the
  distance parity; cyclic projection, best-cost snapshot so a seat between two
  immovable pins stays on a stable side instead of flipping), then the free
  cells are assigned by a feasible corridor: from every fixed seed `s`,
  `L(v)=max(s-dist)` and `U(v)=min(s+dist)` are 1-Lipschitz envelopes, floors
  are lower seeds, and a bounded projection inside `[L,U]` removes the rest. A
  free<->free residual is therefore impossible by construction; only an
  immovable<->immovable pinch keeps one, and it is counted. **N3-a: the result
  is adopted unconditionally** and every moved cell is counted
  (`ROAD_POLISH_MOVED` / `MAX_DELTA` / `DELTA_P90`), never hidden. Do not
  reintroduce a "freeze the outliers" variant: it reinstates a known cliff and
  invalidates the metric the pass exists to make zero. With
  `allowCutIntoTerrain = false` the polish treats `baseP` as a hard floor too, so
  a terrain step no legal no-cut profile can bridge keeps its residual - which is
  what that flag promises; the projection clamps to the LOWER bound, never below
  a floor, to hide one. G1 (2026-10-16, refined 2026-10-17): a seat must always
  be even; when the clamp contains no even value it takes the adjacent even side
  CLOSEST to the neighbouring road (the old unconditional "even above" could
  leave a whole-block gap), and the residual is counted only when the two
  immovable sides genuinely cannot meet. G2: the final verification walks every
  edge; a pinned ENDPOINT no longer suppresses it - only edges INSIDE one plaza
  group are skipped, because that group is flat and moves as one unit. Movable
  plaza group pins are reconciled before the T1.5 shift; T5 / phase-2 pins are
  physically fixed and their residuals stay visible.
- **The seam band extends TOWARD the seam, never narrows against it.** Phase 3
  merges the seam cells' own hard bound into their band outright and grows
  interior cells' bands toward the seam interval along the legal-slope cone
  (one half unit per slice of distance, review 3 P0-A). The review-4 variant
  that INTERSECTED the band with a per-slice cone depended on the reference
  profile carrying the seam height; with the terrain-chase band it empties the
  seam cell's candidates and resurrects the seam cliff (measured: 12 failing
  tests, all seam violations; fix plan v2 A6 deviation, approved by the user).
- **The writer renders the solved profile; every column's height comes from
  `RoadWritePolicy` (fix plan v4 D6-b).** Three per-cell overrides used to sit
  between a legal mask solution and the world and were invisible to every
  mask-internal counter - which is why "different chunks do not match" could not
  be reproduced from the solver side. Now: the written walk surface is always the
  cell's **solved** surface; a solved height outside the world is refused, not
  clamped. What is left of the old "snap onto the real surface" behaviour is the
  guarded single-cell nudge below. The one deliberate exception is a protected
  non-terrain decoration (see the feature-protection bullet): the writer skips
  that block, so one column can intentionally keep a tree/gap where a road,
  fill or clear block would otherwise be.
- **A water crossing is never below the water surface (user decision
  2026-10-15; enforced in the SOLVE since plan E, 2026-10-15).** The water
  counts as SOLID when the road height is determined: an anchor cell's `baseP`
  (the water surface) is a hard floor in `candidatesFor`, `runCandidates`, the
  polish and the conflict sweep, regardless of `allowCutIntoTerrain` - the
  solve no longer produces a soaked profile, so
  `RoadWritePolicy.crossingWrittenWalkSurface2` (`max(profile, water)`) is the
  writer's safety net, not a hoist that fires:
  - profile **at** the water -> the **scaffold IS the walk surface** (user
    decision 2026-10-14): it replaces the road block at the water block's Y,
    which is what makes a crossing read as a plank bridge. The old rule
    (scaffold as support *under* the road block) put it one block under the
    water surface and out of sight.
  - profile **above** the water -> the solved surface is kept and the cell is a
    **forced bridge deck**: no scaffold and **no support fill from the waterbed**,
    however shallow the water is (`WATER_BRIDGE_CELLS`). Never stack a dirt
    causeway up from the bottom.
  - **A water scaffold is a pure deck (user decision 2026-10-15, 纯桥面不支撑):**
    no support fill from the waterbed at all - the water stays under the bridge
    and the old underwater dirt causeway is gone. The placed distance is the
    configured two-value scheme again (user decision 2026-10-16, workspace
    report §2.1-1: 无支撑处统一置 1, 勿顺手修): a sturdy UP face below anchors at
    `waterCrossing.distanceWhenSupported` (default 0), anything else reads
    `distanceWhenUnsupported` (default 1) - one distance everywhere, no
    neighbour propagation; the 3042952 vanilla-mirror computation is reverted.
    Unsupported spans still decay via the vanilla tick (Q04, knowingly
    accepted; do not "fix" it silently).
  A scaffold is a full block, so at the water surface a crossing cell whose
  solved surface is a slab is written half a block high; that half step is
  **accepted** and modelled in `RoadNetworkBenchTest` as `crossingViolEdg`
  (exempted from the continuity gate). Do not "fix" it by snapping the solved
  height - that breaks the half-step chain.
- **Slabs and leaves placed into water go down waterlogged (user decision
  2026-10-15).** Road slabs and leaf railings check the target cell for a water
  source and set WATERLOGGED (`WATERLOGGED_PLACEMENTS` in /vps stats) instead of
  silently displacing the water. Full road blocks are not waterloggable and are
  unaffected; desert villages have no water and need no special case.
- **Clearing re-reads the column at write time and runs to `min(clear cap,
  fresh column top)` (user decision 2026-10-17, refining 2026-10-15).**
  `RoadWriter#clearAbove` / `RoadWritePolicy#clearTopY` end at
  `min(road + maxClearLayers, highest collision/fluid block Y)`, not at the stale
  heightmap top and not at the first air of the contiguous ceiling probe. A cave
  inside the cap is cleared through, so the clear is exhaustive within the cap.
  `maxClearLayers` defaults to **4** (so the cap-formed ceiling is visible);
  `< 0` means unlimited. The lowest collision/fluid block above the cleared
  space is the road's **ceiling** (cap-formed or natural roof); it bounds both
  the single ceiling layer and the kerb wall below it (2026-10-17). A block
  inside a protected building box or classified as a non-terrain decoration is
  never cleared; the clear skips that block (see the feature-protection bullet
  below). The contiguous-ceiling probe `ceilingSurfaceTopY` stays only for the
  over-budget observation.
- **Non-terrain decorations always win their block (user decision 2026-10-17,
  all non-terrain decoration; skip only).** `terrain.FeatureProtection`
  classifies at write time against vanilla's natural-terrain tags (dirt, base
  stone, sand, snow/ice, terracotta, ores, sculk replaceables) plus the common
  terrain blocks (sandstone, gravel, clay, calcite, dripstone, ...). Air and the
  water/lava blocks are not decorations. **Everything else is protected** -
  logs, leaves, plants, crops, vines, furniture, lamps and unknown/modded
  blocks default to protected, so a missing tag protects too much rather than
  destroying a feature. The mod's own road structural materials (mud bricks,
  smooth sandstone, the default cobblestone kerb, scaffolding and the dirt-path
  fill) stay replaceable so the shared-column "low kerb AND high railing" rule
  still works. The road block, support fill, upward clear, kerb,
  ceiling lining, lantern/chain and both railing kinds use
  `RoadWritePolicy#mayOverwrite` and skip the protected block, counting it in
  the `/vps stats` `feature` line (`FEATURE_*_BLOCK_SKIPPED`). This is a
  block-by-block skip, not a whole-column yield: a tree in a road column keeps
  its block and the road/fill/clear simply have a gap there. The kerb probe
  (`VpsGenContext#highestTerrainCollisionOrFluidY`) ignores protected
  decorations, so a canopy cannot pull a retaining wall up to its top, and a
  protected block in the first kerb column breaks the run instead of being
  wrapped. A protected lowest ceiling block suppresses the lining and the
  lantern/chain entirely. Debug door/seam signs deliberately bypass the guard
  (their visibility is the point).
- **Railing at water (user decision 2026-10-15).** Beside a water side column the
  drop threshold is overridden: no railing while the road walk surface is **not
  above** the water surface, and a railing as soon as it is above it - even half
  a block (`RAILINGS_SKIPPED_AT_WATER` counts the suppressed ones). Detection
  scans the side column at the road block's Y and one below.
- **No railing on a movable building's door front (user decision 2026-10-15;
  re-anchored on the template's real doors, F-C/R7, 2026-10-15; narrowed to
  the MAIN door by the door/basis fix plan 2026-10-17, D2).**
  `BuildingComponents#scanTemplates` reads only blocks in
  `#minecraft:doors` as doors (tall grass and large ferns were being treated as
  doors; the skipped lower halves are counted
  `NON_DOOR_LOWER_HALF_SKIPPED`). The main door is the real door nearest the
  template's `building_entrance` jigsaw by horizontal Chebyshev distance, with
  a double door keeping both equally-close leaves
  (`DoorSigns#mainDoorIndices`). The front is one step from that door toward the
  selected road target (`DoorSigns#selectDoorRoadCell`: outside every protected
  footprint, not a seat, outside the door wall); without a target the front is
  the door wall's outward normal and `DOOR_FRONT_FALLBACK` is counted. A piece
  without real doors (farm/pen, unloaded template) keeps the non-RIGID junction
  fallback. The writer skips railings within one block of that front (the 3x3
  box shape is kept per the R8 decision of 2026-10-15) so a doorway can never be
  fenced in (`RAILINGS_SKIPPED_AT_DOOR`); `railing.skipAtBuildingDoors` gates
  it. `door anchor offsets 0/1/2/3/4+` in /vps stats is the junction-to-door
  distance distribution (F-D) that documents why the junction is not a door.
- **Street decor seats ride the solved profile (原版多向道路结构调查 2026-10-15,
  reworked by user decision 2026-10-16, clarified by the user the same day).**
  A street piece's up-facing y=0 jigsaw whose `final_state` is one of
  grass_block/dirt/sand/smooth_sandstone/dirt_path/cobblestone joins the mask
  as a NORMAL solver cell (the four terrain bases plus the 44 dirt_path + 2
  cobblestone road-material seats per version, all in since 2026-10-16): it
  rides the solved profile with `|dP| <= 1` to its neighbours, constrained to
  EVEN walk surfaces (`candidatesFor`/`runCandidates` filter and the unit level)
  so the RIGID decor piece on top shifts by whole blocks. A 4-connected seat
  component is ONE flat unit (two adjacent full-block seats cannot differ by a
  half step, so they must share the level) and it MOVES as a whole during the
  polish - only a T5 decor-pinned seat is immovable. **A seat WINS a shared
  column** (user decision
  2026-10-16: the street piece already provides the base, the road must not
  pave over it) - a road cell on the seat's column (crossroad overlap) is
  replaced by the seat cell; a dropped seat probe keeps whatever claimed the
  column, and the first seat keeps it when two seats overlap. The writer
  renders the seat's vanilla block at the solved height (dirt fill below,
  clearing only up to the old seat block - the decor stands above it) and
  dresses no edges. At T1.5 the decor piece (lamp/tree/hay - a 1x1 non-street
  pool element on the seat's column) is shifted to the seat's solved level by
  `T15Planner#settleSeats` (`SEAT_DECOR_PIECES_MOVED`), which REPLACES the
  lamp's old singleton-building alignment; such singletons are removed from
  the building pass (`isSeatRider`). At T5 nothing may move, so a seat with
  decor pins at the decor's real render level (the phase-2 plaza precedent)
  and a bare seat rides the solve. The old "pin at the probed vanilla height"
  design is gone - a seat must never drag the road to vanilla height.
- **The polish never moves a physically fixed cell, but it DOES move a movable
  unit before placement (2026-10-15, reworked 2026-10-17).** A T5 decor-seat pin
  and a T5/phase-2 plaza pin are immovable: the free side takes the correction
  and two immovable pins keep their counted residual. On the T1.5 phase-1 solve
  no plaza piece has been placed yet, so a whole plaza group is a movable unit:
  the pairwise projection shifts it (and the group pin) until the road can meet
  it, and `T15Planner` renders the adjusted level. Do not freeze phase-1 plaza
  groups again - that is the `PLAZA PIN` seam sign.
- **Acceptance after G2 (four-item confirmation §6.3, 2026-10-16).** With pin
  edges counted, every free<->free edge is legal by construction now; the only
  residues left are between physically immovable pins (a T5 decor seat /
  phase-2 plaza pair) or a floor-limited no-cut/water cell, and each is counted
  and visible (the solver's count equals an independent edge walk). The bench's
  plaza/seat scenarios and `RoadPolishTest` pin the three 2026-10-17 failures:
  two movable plaza groups must meet through their shared road cell, adjacent
  bare seats must share one even level, and an ordinary road cycle must leave no
  free residual. "Try harder to keep the old residual" is not an acceptable fix:
  move the movable interval instead; plan B (even-level plaza flattening) stays
  rejected as a default.
- **Debug door signs are opt-in, default off (user request 2026-10-16, gated
  2026-10-17; main-door narrowing and NO FRONT by the door/basis fix plan
  2026-10-17, D2/D4).** `debug.doorSigns` must be true; both the sign list and
  the writer are gated, so the off state costs nothing. Every movable
  building's **main door** gets one standing oak sign: line 1 the door X/Z with
  `baseWalkY + dy` (the main piece's `bbox.minY + getGroundLevelDelta`), line 2
  the selected road cell's X/Z and its solved walk surface (x.0/x.5, numbers
  only, locale-free integer formatting), **line 3 the actual door floor Y** of
  the template's lower door half after the shift, and line 4 `NO FRONT` when the
  door had no eligible outward cell. The selected cell is the SAME `DoorTarget`
  the alignment and the railing exemption use
  (`DoorSigns#selectDoorRoadCell`): nearest by horizontal Manhattan distance,
  on the outward side of the door wall, excluding seats, 2D protected
  footprints and the writer's 3D `insideBuilding` columns. Cells inside a
  building are therefore never the sign's road cell even when vanilla routed a
  side street through the house. A NO FRONT door still gets a sign: line 2 names
  the unfiltered nearest whole-network cell and line 4 says `NO FRONT`; the
  building is not moved for it (user decision 2026-10-17). Signs are placed
  after the road cells of their own chunk. Debug visibility beats fidelity (user
  decision 2026-10-16): the sign replaces whatever occupies the target cell and
  may float, so EVERY main door gets its sign; `door signs placed / no road` in
  `/vps stats` now means main doors without an eligible front. The water-seat
  base rules are deliberately untouched by it.
- **Debug seam signs are opt-in, default off (user request 2026-10-16, gated
  2026-10-17).** `debug.seamSigns` must be true; both the sign list and the
  writer are gated. `SeamSigns`
  (pure) walks every 4-adjacency edge of the solved mask and emits one sign per
  "seam cell": an edge whose two cells come from different sources - two
  different `street/terminator` pieces (`pieceIndex` differs), a plaza cell next
  to a non-plaza cell, or a decor-seat cell next to a non-seat cell. The sign is
  anchored on the more structural cell (plaza > seat > normal, then the lower
  packedXZ) and names the differing-kind neighbour with the largest `|dP|`
  (ties keep the lowest x/z). The writer places one standing oak sign one block
  ABOVE the anchor's written road block (it does NOT replace the road block, only
  the air above it): line 1 "anchorX anchorY anchorZ", line 2 "farX farY farZ"
  with Y = `solvedP / 2` (x.0 / x.5), and - only when `|dP| >= 2` (more than half
  a block, i.e. the solver's continuity promise broke) - line 3 the known cause
  and line 4 its magnitude (`d`). The causes, in priority order: `PLAZA PIN`
  (a pinned flat plaza group), `SEAT PARITY` (a decor seat on an even surface),
  `WATER FLOOR` (the water-surface floor), `WORLD EDGE` (a seat clamped at the
  world edge), `NO-CUT FLOOR` (a `baseP` hard floor with `allowCutIntoTerrain=false`),
  else `POLISH RESID`. `seam signs placed / step violations` in `/vps stats`
  documents the volume and the illegal seams.
- **Plan A: a building always wins its column (user decision 2026-10-15).**
  `VpsVillageSolution#buildingBoxes` carries the 3D boxes of every protected
  piece - movable buildings plus the immovable hay piles
  (`BuildingComponents#protectedBoxes`; read after the T1.5 shift, so they
  describe where the pieces really are).
  `RoadWriter#insideBuilding` skips a claimed column whose road block lands inside
  one - no road block, no support fill, no clear - so smoothing can never
  overwrite a building body (`BUILDING_CELLS_SKIPPED`, and the column yields).
  `roadSmoothing.protectBuildings` gates it.
- **Every fill/cut decision is a per-cell directional probe, never the heightmap
  top (user decision 2026-10-14).** `terrain/ColumnScan` + `VpsGenContext`:
  `groundSurfaceTopY` scans **down** from the road block for the first standable
  block (the fill/bridge base, and the surface a nudge snaps to);
  `ceilingSurfaceTopY` scans **up** for the top of the contiguous terrain (the
  cut/tunnel ceiling). A canopy or a floating layer above an open road must not
  fake a fill, and an overhang above a solid one must not fake a cut. The raw
  `WORLD_SURFACE_WG` read stays for `recordDeviation` and `runBuildingGuard`
  (observations) and for the crossing predicate - never for a write decision.
- **Every kerb/railing decision is a side-column probe from the road block's own
  Y, in the direction that decides it (user decisions 2026-10-14, reworked
  2026-10-15).** Two probes, memoised per (column, road level): the kerb
  measures the column's **height-spectrum top** (scan up from the road block's
  level THROUGH air gaps - an open edge with a shelf higher up is wrapped too),
  the railing measures the **walkable ground below** (scan down, so an overhang
  cannot hide a drop). The old code read `WORLD_SURFACE_WG` for the side column,
  so an overhang faked a kerb up to the canopy.
- **Every road cell dresses its own edges (user decision 2026-10-15).** The
  review-4 one-owner-per-side-column rule (`highestRoadPerSideColumn`) is gone:
  a column shared by a low and a high road gets the low road's kerb AND the high
  road's railing. Do not reintroduce a single-writer gate "to keep kerb and
  railing apart" - that combination is the point. The drop metric still counts a
  column once (`countedEdgeColumns`).
- **The kerb probes two cells outward but wraps only the first (user decision
  2026-10-17, reworking 2026-10-15).** The wrap still starts at
  `roadTopBlockY + 1` and skips air gaps. The trigger is now: at every layer
  from one above the road to the highest **terrain** collision/fluid block in
  either of the two outward columns, if **either** cell has a non-empty
  collision shape or a fluid, the first cell gets the kerb material. Protected
  non-terrain decorations are ignored by the probe and skipped in the first
  cell (a decoration there breaks the run); building boxes are never replaced.
  An air first cell is filled up
  to the probe top, so a one-block setback becomes a retaining wall. The
  material is no longer restricted to `#minecraft:dirt`. `kerb.minDropHeight` is **kept**: the two-column probe top must
  still rise more than the threshold above the road. The bottom course level
  with the road block stays vanilla - that is the chosen start level, not a bug.
  **The wall stops one block below the road's ceiling** (2026-10-17,
  `RoadWritePolicy#kerbTopY`): the ceiling layer belongs to the road column
  only, and the kerb must not climb the hillside above the tunnel. With no
  ceiling (open sky) it keeps wrapping to the probed side top. A leaf **kerb
  railing** is placed at the top of every contiguous wrapped run (terrain top
  and mid-column air-gap tops); a run artificially cut off at the ceiling or
  the world edge gets no cutoff-top leaves.
- **A plaza piece's own interior is never dressed, and a kerb run's along-road
  end is capped (user request 2026-10-17).** `RoadCollector` records every y=0
  column of a `town_centers` piece - ALL blocks, including the well/fountain
  water and jigsaw holes that are not road cells - in
  `RoadNetwork#plazaFootprint`. The writer skips a side whose outward cell is
  in that footprint, so the central well/fountain mouth is never wrapped and
  plugged; the plaza's outer boundary (outside the piece footprint) still gets
  its kerb. Separately, when the road actually continues along the wall (a
  road cell exists at `cell + T`) and the next road cell's side cell
  already sees open sky above the road, the writer closes the run's end ABOVE
  the existing wall top: {@code wallTop + 1} is always placed, the layers above
  it use the same probe-2-wrap-1 rule (probe the cap cell and its outward cell),
  and the leaf railing goes on top. It never re-fills the column from the road,
  and an end wall (the road's forward side) never grows a cross-road column.
- **Only the lowest block of the ceiling is lined, and only above the road
  (user decision 2026-10-15, restricted 2026-10-17).**
  `RoadWriter#lineCeiling` probes only the road's own column with the
  collision/fluid predicate and writes exactly one layer - the first
  collision/fluid block at/above the clear end. At a cap-formed ceiling that
  bottom block becomes **glowstone** on the middle cell of a straight 3-wide
  road (not at a corner/junction, never on a side cell or a non-3-wide road);
  the roof thickness above it is untouched and no lantern is placed. If the
  clear cap stops in air and a natural cave roof lies above, only the roof's
  lowest block is lined with the kerb material and the clear-cap cell gets a
  hanging **lantern** plus an **iron chain** up to the roof (no glowstone).
  Building blocks and non-terrain decorations are never replaced; if the lowest
  ceiling block is a protected decoration, the whole ceiling treatment (lining,
  glowstone, lantern, chain) is skipped. `RoadNetwork#threeWideMiddleCells`
  implements the straight-3-wide test on the arm slices. Plaza cells never
  reach the ceiling code - the run above a plaza platform is the fountain.
- **Building shift rules (user decisions 2026-10-14 and 2026-10-15):**
  - a movable piece is a RIGID `SinglePoolElement` (a building) and nothing
    else (`BuildingComponents#isMovable`). The hay pile is **not** moved any
    more (user decision 2026-10-16, report §3.3-2 "非预期，干草堆不应搬动" -
    reverting the 2026-10-14 rule): a standalone pile stays where vanilla put
    it and keeps its protected box (`BuildingComponents#protectedBoxes`), so
    the road still yields to it; only a pile standing ON a mask seat still
    moves, because it rides the seat (see the seat bullet). The decor pool's
    trees and flowers are still deliberately **not** moved - a moved tree
    fights the vanilla terrain adaptation that raises the ground under it. A
    `town_centers` piece is **not** a movable building any more (plan C): the
    plaza group has its own shift;
  - the alignment target is the road cell selected at the component's **main
    door** - the real lower door half nearest the template's
    `building_entrance` jigsaw (door/basis fix plan 2026-10-17, D2; the old
    template scan treated tall grass/large ferns as doors, and every side/back/
    upper door entered the median). A double door keeps both leaves. The
    selected cell is the nearest eligible cell on the door wall's outward side
    (`DoorSigns#selectDoorRoadCell`); it excludes seats, protected 2D
    footprints and the writer's 3D `insideBuilding` columns, so a hidden
    vanilla side street through a house can no longer win. Junctions
    (`destProjection` not RIGID) are only the fallback for pieces without real
    doors (`T15Planner#desiredShiftFromJunctions`). No outward cell means
    `NO_FRONT_ROAD` and the building is not moved for that door (D4);
  - the alignment reference is **`baseWalkY = mainPiece.bbox.minY +
    getGroundLevelDelta()`** (door/basis fix plan 2026-10-17, D1). That is the
    exact level `JigsawPlacement` and `Beardifier` align a RIGID piece to; the
    old "median minY + 1" was only an approximation. The main piece is the
    largest footprint among the component's non-1x1 pieces (ties: larger Y
    span, then lower packedXZ); `BASE_PLANE_MISMATCH` counts components whose
    other non-1x1 pieces disagree. `BuildingShift#desiredShift` takes the base
    walk level directly and adds no `DOOR_WALK_OFFSET` any more;
  - **R2-A (user decision 2026-10-16) is superseded by D1**: the 1x1 decor
    exclusion stays meaningful, but the production base no longer comes from a
    median at all. `BuildingShift#componentBaseY` is kept for the historical
    unit test only; `BASEY_DECOR_UPPER` stays as a legacy observation counter;
  - every door road cell presents its **SOLID level** (`BuildingShift#solidTargetP`,
    plan E 2026-10-15): a full block its own top, a bottom slab counts as AIR
    and presents the level below - the house's Y0 layer sits flush with a
    full-block road and half a block below a slab road. All targets are
    whole-block levels, so the interval is a single exact value or empty;
  - the shift is chosen from the **legal interval** (plan D 2026-10-15): the
    shifted Y0 top must stay within half a block of every door road cell's
    solid level; inside the interval the shift closest to zero wins; the median
    is only the fallback when the interval is empty (a mixed full+slab door).
    `buildingShiftMedianWingBlocks` (default 0 = off) optionally clamps the
    shift around the median scheme's shift;
  - the overlap guard compares **only against other movable pieces** and only
    on a **strict intersection** (`T15Planner#overlaps`, F-A 2026-10-15: the old
    `<=`/`>=` counted touching boxes, so any flush neighbour - even a 1x1 hay
    pile - could pin the component at the vanilla position). A street or
    terminator box is not an obstacle at all; hay piles left the obstacle set
    when they stopped being movable (2026-10-16) - the road yields to them,
    building shifts no longer do;
  - `BuildingShift` has **no `SPAN_TOO_WIDE` refusal** any more. It left the
    building at the vanilla terrain height beside a smoothed road, which is the
    symptom it was supposed to avoid.
- **The second authoritative alignment runs after the final re-solve and then
  the road is frozen (door/basis fix plan 2026-10-17, D5).** The first pass still
  runs and still triggers the post-shift re-solve (it lets the beard boxes
  follow the moved pieces and keeps the plaza triggers). Afterwards
  `settleSeats` runs on the FINAL network, then every component is aligned
  again against that network with the main-door targets; the move applied is
  `desiredTotal - component.dy`, so it is only the ADDITIONAL shift.
  `Component#syncedDy` makes `syncJunctions` propagate exactly that additional
  amount; syncing the total again would double-move the junctions. No
  `RoadCollector#collect` / `RoadSmoother#solve` may run after this pass:
  `shiftedComponents` / `buildingBoxes` / door signs all read the final pieces
  and the final network. `SECOND_ALIGN_MOVED` counts the components the second
  pass actually added, and `DOOR_TARGET_CHANGED_BY_RESOLVE` counts main doors
  whose selected cell XZ or solved surface changed between the two networks.
- **The single-cell surface nudge is allowed, and it is guarded (AGENTS.md rule,
  fix plan v4 D6-b).** A cell whose profile write threw, or whose solved height
  left the world bounds, may be written at the real surface - but only if no
  neighbouring walk surface moves more than half a block
  (`nudgeKeepsNeighboursLegal`) and the cell is not beside a moved building
  (`nearBuildingEntrance`). Otherwise it is **refused**: nothing is written and
  `REFUSED_WRITES` counts it. Both outcomes are in `/vps stats`. What stays
  forbidden is re-solving: moving whole runs or whole chunks off `solvedP`.
- **An over-budget cell keeps its solved height** (fix plan v2 R3 / N7): a cell
  beyond the fill/cut cap is rendered as a bridge deck or a tunnel/open cut and
  counted (`FALLBACK_REANCHORS`), never snapped onto the real surface - that snap
  manufactured the `|dP| >= 2` steps N1 forbids.
- **A split mask is solved per component, never declined (fix plan v4 D1-e,
  reworked 2026-10-14; user decision 2026-10-16: 只允许 WARN 和退到分区求解，
  禁止全村退回原版).** Real village masks split routinely and not because of a
  probe failure: vanilla routes side-streets through houses, which are not in the
  mask, so one village's street clusters are genuinely separate 4-connected
  components. The components have no edge between them, so each is solved and
  written on its own (the polish pass already seeds one walk per component) - the
  old rule, "decline the whole village when the mask splits", turned a few
  separated side-streets into "the mod does nothing", which is the symptom it
  caused in game. A genuine hole (a dropped probe column) still shows up in
  `dropped cells` / `widest break` and costs one vanilla column; probe failures
  re-probe the noise caliber first (`ProbeHeights`), which makes that rare.
  `split masks` / `largest component` in `/vps stats` stay as observations.
- **`town_centers` plazas are part of the road network, pinned flat, and render
  themselves (fix plan v4 D5-c + plan C, user decision 2026-10-15).** The mask
  regex matches them, but only road-material y=0 cells are taken
  (`TOWN_CENTER_NON_ROAD_SKIPPED` counts what was skipped). Each 4-connected
  plaza group pins to ONE flat EVEN walk surface - the group's median terrain
  height on the T1.5 first solve, the level the pieces really render at on the
  post-shift re-solve and on the whole T5 path. The median is rounded down to an
  even level because a plaza platform renders full blocks. On the T1.5 phase-1
  solve the pin is MOVABLE: the polish's pairwise unit projection reconciles
  nearby groups/seat components (path-distance constraints) before anything is
  placed, so the road always meets the group with `|dP| <= 1`; the dedicated
  group shift then moves the owning pieces (well rim and fountain riding along
  as one rigid body) onto that level. On T5 / phase 2 the pin is the physical
  render level and stays hard. The writer **never** writes a
  road block, a fill or a clear on a plaza cell (the clear would raze the
  fountain): the vanilla rigid platform IS the solved surface by construction,
  and only the edge details run on the plaza boundary
  (`PLAZA_CELLS_VANILLA` / `PLAZA_GROUPS` / `PLAZAS_SHIFTED` in `/vps stats`).
  **R1-1 (user decision 2026-10-16): the post-shift phase-2 re-solve is not
  skipped just because nothing moved.** A plaza group whose dy the overlap or
  world-height guard pushed back to zero keeps its phase-1 pin (the noise median)
  while its rigid platform still renders at the vanilla level, so the two
  disagree - the phase-2 re-pin (which follows the real render level) is exactly
  what corrects the plaza edge. The guard is "no building moved AND no plaza group
  moved AND no plaza group's phase-1 pin differs from its real render level"
  (`T15Planner#plazaPinsMismatch`); `PLAZA_OVERLAP_GUARDS` counts the clamped
  groups.
- **The solved village is cached on the anchor chunk, not only on the start
  instance (plan E, 2026-10-15).** A village spans many chunks and later
  placements can meet fresh `StructureStart` instances; the extra instances used
  to run a full T5 re-solve (no building move!) and the writer consumed a
  network that did not match the shifted pieces. `VpsPipeline`'s LRU cache keys
  on (dimension id, Structure instance, anchor chunk) - every instance of the
  same village shares one solution, and `/vps config reload` clears it.
  `hasCustomRegex` must compare against `RoadSmoothing.DEFAULT_ROAD_PIECES_REGEX`
  (the constant the field default uses), not a copied string.
- **All drop criteria are computed in half-block walk-surface units** (`S = 2 *
  walk surface`); never use `roadTopBlockY + 1` as the walk surface (it is 0.5
  too high for a slab). The pure helpers live in `RoadWritePolicy`
  (`roadWalkSurface2` / `kerbApplies` / `railingApplies`).
- **T1.5 runs during STRUCTURE_STARTS**, not FEATURES: `MixinStructureGenerate`
  hooks `Structure#generate` RETURN (pieces assembled, bounding box not yet
  cached) and `MixinChunkGenerator` captures the MinecraftServer and the owning
  dimension id into `VpsGenThread` around `ChunkGenerator#createStructures`. If
  the server cannot
  be resolved, T1.5 must move nothing and let the T5 solve run at FEATURES
  (never "default allow" - a disabled world must not get its buildings moved).
- **RIGID building components move as a group**: `BuildingComponents` unions
  movable RIGID pieces (buildings only since 2026-10-16) joined by a
  RIGID-to-RIGID jigsaw junction; one `dy` per component.
  `PoolElementStructurePiece#move` does NOT touch junctions, so
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
  chunk (`WorldGenRegion`); beyond that use the noise base height. **The solve
  runs during `STRUCTURE_STARTS`, where no chunk of the village has terrain yet** -
  no chunk status below `STRUCTURE_STARTS` produces one, and `TERRAIN`/`FEATURES`
  both *require* `STRUCTURE_STARTS @ 8`, so a real heightmap is not merely
  unwindowed here, it does not exist. The default T1.5 path is pure noise for that
  reason and is a pure function of the seed. On the T5 fallback the real heights
  that do exist are read inside a +/-1 chunk window, and since fix plan v4 D2-c
  that window is anchored on the **village's bounding box**
  (`ProbeWindow.centerChunk`), not on the chunk that triggered the solve:
  `ProbeWindow` deliberately takes no trigger argument, so trigger dependence is
  not representable. `roadSmoothing.probeCaliber = ALL_NOISE` drops the window
  entirely (noise everywhere, real heightmap only at write time). The two calibers
  meet at the window edge: `RoadCollector#alignProbeBoundaries` pulls
  noise-probed columns directly adjacent to a disagreeing real column onto the
  real value before the network is built (fix plan v2 R5).
- **The promises D7/D9 make are about the MODEL, not the world**
  (fix plan v4 R2-4). `allowCutIntoTerrain = false` means `P >= baseP`, and
  `baseP` is the noise-model height: the solve happens before the village has
  terrain, so carvers, surface rules and terrain adaptation are all invisible to
  it. A no-cut road can therefore still be below the real terrain in the world.
  **Do not "fix" this by changing what `baseP` means** - that reintroduces the
  caliber difference into the solver. The world-level violation is measured
  instead: `CUT_BELOW_TERRAIN_CELLS` / `FILL_ABOVE_TERRAIN_CELLS` and the
  deviation block in `/vps stats`.
- **A deviation WARN is not a degradation switch (fix plan v4 R2-3).**
  `RoadWriter#recordDeviation` measures `written walk surface - real surface` and
  warns once past `surfaceDeviationWarnBlocks` (default 4 whole blocks). It never
  changes geometry and never falls back to vanilla roads - the road keeps
  following the solution. The mask-integrity gate that used to hand a split
  village back to vanilla is gone (1f95d46, confirmed by user decision
  2026-10-16: WARN + per-component solve only, a whole-village decline is
  forbidden); nothing else hands a village back to vanilla.
- New counters are wired into `/vps stats` in the column they belong to
  (integrity / defects / model gap / solve / buildings), and the file's
  hard-constraint list is kept in step with them.
- **R1-2 (user decision 2026-10-16): a y=0 street block that is non-solid or
  replaceable is NOT road.** `RoadCollector#isRoadSurfaceCell` (the default
  `TEMPLATE_Y0_SOLID` rule) still takes "any y=0 non-marker block" as road, but a
  block that `canBeReplaced()` (a plant such as a dead bush, a torch, a snow
  layer) is excluded by block semantics - not by a material whitelist, so a
  legitimate street material is never dropped. `TEMPLATE_Y0_NON_SOLID_SKIPPED`
  and `/vps debug y0` are the y=0 histogram the decision is measured against.
- **R1-3 / R1-4 (user decision 2026-10-16, observation only):** a dropped seat
  probe is counted (`SEAT_PROBE_DROPPED`) and a seat whose normal write threw now
  falls back to the SEAT block instead of road material
  (`RoadWriter#writeSeatFallback`, `SEAT_WRITE_FALLBACK`); a seat clamped at the
  world edge by `settleSeats` is counted (`SEAT_EDGE_CLAMPED`) - the clean
  pre-solve pin waits for the edge-village frequency.
- Write blocks with `Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE` (vanilla
  structure flags): no client updates during worldgen and heightmaps stay
  untouched (so our own probes stay consistent).
- `ScaffoldingBlock` re-computes `distance` on its first tick; unsupported
  spans decay. That is a documented known issue - do **not** "fix" it silently.
- Kerbs and the ceiling use the collision/fluid rule, not a material whitelist:
  any **terrain** block with a non-empty collision shape or a fluid counts, and
  the kerb replaces any non-building, non-decoration block in its first column
  (including air). Do not reintroduce the dirt-tag-only restriction.
  `kerb.replaceableBlocks` is kept only for config compatibility and is no
  longer read.
- **The written road surface is mud bricks for the non-desert styles (user
  decision 2026-10-17).** The half-step slabs stay `mud_brick_slab`, the support
  fill below the road stays the vanilla `dirt_path`, and the desert
  `smooth_sandstone` road/slab are unchanged. Do not retexture the fill or any
  plaza/street-decor/building dirt path: `RoadMaterialTable` keeps separate
  `roadBlock` (surface) and `fillBlock` (support) materials for exactly this.
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

`RoadNetworkBenchTest` is the fix plan v4 M0 bench and is part of `test`: the
three-dimensional rig (4 terrain shapes x 4 network shapes x 2 probe calibers)
with the integrity / defect columns as hard assertions and the style column
recorded and printed. It is pure Java and drives the production decomposition,
solver and writer policy, so a fix moves the numbers rather than a mirror. Its
terrain parameters are hard-coded; do not widen an assertion to make it green.
The plaza/seat scenarios (2026-10-16) run separately: they assert the new
acceptance (residues visible, worst <= 2, each attributable) instead of the
zero gate, and print the two upgrade-decision frequencies.

`RoadPolishTest` and `RoadMaskIntegrityTest` pin the two invariants the bench
cannot state on its own: the profile is legal by construction, and a failed probe
or a split mask never silently costs a column.

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

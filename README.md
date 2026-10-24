# Village Path Smoothed

**English** | [中文](README.zh-CN.md)

Rewrites vanilla village road generation. The vanilla road jumps a full block
wherever the terrain does; this mod rebuilds it as a continuous road made of
**slab steps** (half blocks, not stairs), so neither the forward nor the
lateral direction ever drops more than **half a block**. Roads that climb
slopes cut into the hillside and are held by stone kerbs, roads over water
become scaffolding bridges, and dangerous edges get leaf railings.

- **Server-side worldgen mod.** Install it on the server (or in singleplayer);
  clients do not need it.
- **Per-world switch.** The Create World screen shows a "Village Path
  Smoothed: Enabled" toggle. Pressing "Create" binds the toggle value to the
  world being created (matched by world name); that world's own switch is
  written to `<world>/village_path_smoothed.json` on its first chunk
  generation. Existing worlds, and worlds created without touching the toggle,
  keep their own setting or fall back to the global default. **The per-world
  switch is authoritative**; the global config only seeds new worlds. Toggle
  in game with `/vps world on|off`.
- **New chunks only.** Like vanilla worldgen, the mod only shapes chunks while
  they generate; existing chunks keep their vanilla roads.

## Features

### Road surface

The road mask is rebuilt from the street templates of every village style,
then a height profile is solved for it.

- **Half-block steps.** Adjacent cells differ by at most half a block, forward
  and laterally. The default objective (`SMOOTH_FIRST`) ranks cliff penalty >
  total step count > deviation from the terrain — "smooth" means "as few
  half-block steps as possible": short bumps are levelled wherever the
  candidate band allows, sustained grades are followed at half-block pace, and
  cliffs that are geometrically impossible for half-steps get one short,
  vanilla-style jump (soft-constraint penalty, counted in `/vps stats`).
- **What counts as road.** The mask is the **connected street surface**: the
  y=0 cells reachable from the street jigsaw connectors, plus the decor seats.
  Farmland, water channels and terracotta trim inside a street template are
  scenery, not road: they keep their vanilla blocks and the writer builds no
  kerb into them. A road that passes several blocks above such scenery still
  gets its drop railing on that edge; at the scenery's own level nothing
  changes. A connector base that is plain dirt still counts, so a crossroad
  seam keeps its column.
- **Materials.** Full-block road cells are written as **mud bricks**, the
  half-step slabs stay **mud brick slabs**, and the support fill below the
  road stays the vanilla dirt path. The desert's smooth sandstone road and
  slabs are unchanged. Only the mod-written road surface is retextured — dirt
  paths in plazas, street decor, buildings and the fill under the road are not.
- **Sunken roads.** Continuous one-block-per-cell slopes are followed at
  half-block pace: the path cuts into the slope and the kerbs hold the walls.
- **Decks and tunnels.** Long grades that cannot be carried by fill or cut
  become **bridge decks** (fill cap) or **lined tunnels** (clear cap) instead
  of being shattered into per-cell steps.
- **Per-cell fill and cut.** The fill/cut decision is made per cell from a
  **downward** probe, never from the heightmap top: a canopy or a floating
  layer above an open road no longer forces a fill, and an overhang above a
  solid one no longer forces a cut. Neither the clear nor the kerb replaces a
  protected building block or a non-terrain decoration. The raw heightmap is
  still read for the deviation statistics and the floating/buried guard, which
  are observations only.

### Buildings follow the road

Before the village's bounding box is frozen, each movable rigid component is
shifted vertically so its vanilla base walk level
(`main piece minY + groundLevelDelta`) meets the road at its **main door** —
the real door nearest the template's `building_entrance` jigsaw.

- Only blocks in `#minecraft:doors` count as doors (tall grass and ferns do
  not), and a double door keeps both leaves.
- The selected road cell must lie outside every protected building footprint,
  must not be a decor seat, and must lie on the outward side of the door wall.
  If there is no such cell the component is not moved (`NO FRONT` on its debug
  sign when `debug.doorSigns` is enabled).
- The shift moves the whole rigid component at once and its jigsaw junctions
  are synced, so the bearding stays around the building; the shift is a pure
  function of the seed.
- After the buildings move, the road is re-solved once against the moved
  footprints; a second authoritative alignment then aligns the components to
  that final network and the road is frozen.
- Floating or buried components are only counted (`/vps stats`), never
  repaired (`buildingGuard`).

**Plan A protects the real building body, not the template bounding box.** The
protected cells are the building blocks the template really renders, plus
interior air that meets a building block along at least five of its six axes
before leaving the template (a normal room's centre counts; an open edge does
not). The empty part of the old box — a courtyard, an empty template corner,
the air under an eave — no longer blocks a road block, support fill or clear,
and there is no extra Y margin. If a template cannot be read, the piece falls
back to its whole bounding box, so an unknown or modded building never becomes
unprotected.

**Street decor** is part of this building pass, not a plan-A bbox obstacle:

- The writer protects the decor's **actual template blocks** (including its
  cobblestone/snow bases) and still writes the road and the support fill below
  and around them, so a 3×3 lamp no longer leaves holes under its empty bbox
  columns. This applies to every recognised decor piece, including a house
  lamp that contributes only a pinned fixture base. If a decor template/pool
  cannot be read, the piece is excluded from plan A and its readable blocks
  are protected (`resolve failed` in `/vps stats`) instead of falling back to
  its whole bbox.
- A multi-block decor pins its **full base footprint** (seat, actual blocks
  and the air columns over road) flat at the base walk level at T5; at T1.5
  that footprint is one **movable flat unit** in the final solve and the decor
  settles on the level it lands on. The base is part of the solve — a fixed
  shape, vertically movable, with an even surface. A 1×1 RIGID decor piece (a
  lamp) still rides the seat exactly like before.
- A decor is connected to the road network only through **horizontally
  (4-)adjacent solve cells** outside its own base footprint (the seat counts
  as a cell), and every such neighbour is checked against the half-block rule;
  a piece with no external neighbour counts as isolated. Its base group is the
  4-connected part of its footprint that contains the seat/actual base cells,
  so a disconnected bbox ring cannot pull the decor off its seat.
- Vanilla can leave the lowest decor block (the child jigsaw's final base) air
  at a street seat; the writer restores exactly that cell (`base restored` in
  `/vps stats`), which closes the lamp-base gap without moving the piece.
- Decor used outside a street seat (a house lamp) becomes a **pinned non-road
  fixture base cell**: the solver re-solves the four surrounding roads to
  within half a block of the base (`fixture base cells` in `/vps stats`).
  Diagonal and lawn seats are therefore isolated, not height-linked.

**Hay piles, trees and flowers (`FeaturePoolElement`) are placed by vanilla
and are not vertically movable**: they never ride a seat, never pin one and
are never moved. A pile's really placed blocks are still protected by
position, so the road yields to it block by block.

### Structure-ground overlaps

When a road cell and a structure ground claim the same column, the claimed
column's vanilla y=0 block is suppressed. Only a **street fixture base** and
an original **fixed-shape farm house** (`village/<style>/[zombie/]houses/*farm*`,
RIGID — its whole y=0 ground including farmland, water and dirt paths) take
part; the scattered farmland inside savanna street templates is excluded and
stays street scenery.

The overlap cells of one source structure form one flat, vertically movable
unit in the solve, starting from the structure ground's height. The writer
renders that ground at the solved level instead of a road and moves the
template's above-ground blocks (crops, the fixture base) with the same
whole-block delta, so the roads and the structure surface still meet. At T1.5
a fixture base may move as a whole; at T5 it is physically fixed (the farm
ground is writer-rendered and still follows the solve). `fixture overlap
cells`, `farm overlap cells` and `ground overlap written` in `/vps stats`
observe this.

### Kerbs and railings

Each side is probed **from the road block's own height**, and the two cells
outward are probed together (probe-2-wrap-1: probe two, wrap the first).

- **Kerb wall.** At every layer where either of the two probed cells holds a
  block with a collision shape or a fluid, the first cell gets the
  village-style stone (plains/snowy/taiga cobblestone, desert smooth
  sandstone, savanna terracotta). The old drop threshold (`kerb.minDropHeight`)
  still applies. An air first cell is filled up to the probed top, so a
  one-block setback becomes a retaining wall; the material is no longer
  limited to the dirt tag. Building blocks and every non-terrain decoration
  (logs, leaves, plants, crops, vines, furniture, lamps, modded blocks) are
  never replaced: the kerb skips the decoration layer and breaks the run
  there, so a decorated side column stops the wall instead of being climbed. A
  protected building cell in **either** probed column interrupts the run at
  that layer, so the wall never passes through building layers. A wall whose
  second layer is vacant at both probed cells (far decorations count as "not
  found") is not built at all — the single-block wall a slab road's 1.5-block
  rise used to produce is gone. Where the road has a ceiling (a cap-formed
  roof or a natural cave roof), the wall stops one block below it; open sky
  keeps the wrap-to-side-top behaviour.
- **Kerb railing.** One persistent-leaf layer goes on top of every contiguous
  wrapped run — the natural terrain top and every mid-column air-gap top. It
  never enters a building and never enters the 3×3 in front of a movable
  building's door (the drop railing's doorway rule). A run artificially cut
  off at the road's ceiling or the world edge gets no cutoff-top leaves.
- **Plaza interior.** A side that points into a `town_centers` piece's own y=0
  footprint — the well/fountain water and the jigsaw holes, which are not road
  cells — gets no kerb or railing, so the kerb never wraps the central well's
  water column and plugs the well shut. The footprint registers the template's
  **real y=0 blocks only** (vanilla stores air as palette entries too, and
  those empty corners are excluded), so an elevated road crossing a template's
  empty corners gets its normal kerb and railing. The plaza's outer boundary
  is unaffected.
- **End caps.** Where the road keeps going past a wall's along-road end, the
  end is judged on the **next side cell alone** (the sky-visible cell): it is
  open when there is no terrain above the current wall top. The cap is written
  on the **wall's own end column** and turns upward from one above the wall
  top with the same probe-2-wrap-1 rule — it wraps every layer up to the
  probed terrain top of that column and its outward neighbour, with a leaf
  railing on top of each wrapped run. It never writes into the next road
  cell's side column (that cell's own drop-railing target), never re-fills the
  column from the road, an end wall across the road gets no sideways column,
  and an actual road end (no next road cell) is untouched.
- **Drop railing.** Scan **down** from the road edge to the first standable
  ground: when the road is at least **2 whole blocks** above it, persistent
  leaves (plains oak, desert/savanna acacia, snowy/taiga spruce) are placed on
  the road edge at the road block's height plus one; a protected decoration in
  the target cell is skipped instead. A street scenery column (farmland,
  channel water, terracotta) is judged on the same scan, so an elevated road
  over it is railed too — only the kerb stays suppressed toward scenery.

Every road cell dresses its own edges: a column shared by a low and a high
road gets the low road's kerb **and** the high road's railing.

### Ceilings and lighting

Where a road sits deeper than the clear cap (`maxClearLayers`, default 4)
below the ground, it tunnels and gets a ceiling.

- **Ceiling lining.** The ceiling probes only the road's own column, with the
  same collision/fluid rule, and the clear end is re-read at write time from
  the real column. **Only the lowest block of the ceiling is lined** — the
  roof thickness above it is left untouched.
- **Tunnel mouths.** When any 4-adjacent **road** cell (along the road or
  laterally) sees open sky above its own walk surface, the ceiling's end turns
  upward into a kerb face in the road column, from one above the ceiling layer
  to that column's terrain top, with a leaf railing at each run top. Even when
  the upward wrap is zero blocks — no terrain above the ceiling in the column,
  or the first layer already protected — the end still attempts its leaf at
  the first layer above the ceiling. A building cell in either probed column
  interrupts the face like anywhere else. The open cell must still be part of
  the road net: an off-road open cell already carries the road's own side
  kerb, and wrapping there would stack a kerb on top of that kerb.
  Probe-2-wrap-1 applies: the first cell is the road/face column, the second
  is one cell **into the hill** (opposite the open cell, along the road or
  laterally), never the open cell itself. A layer where both probed cells are
  air stays air, so the face cannot place floating cobblestone.
- **Lighting.** When the cap forms the ceiling, its bottom block becomes
  **glowstone** on the middle cell of a straight 3-wide road. A natural cave
  roof beyond the cap has its lowest block lined with the kerb material and
  instead gets a hanging **lantern** in the clear-cap cell, connected to the
  roof by an **iron chain**. Side cells, non-3-wide roads, corners and
  junctions get no light. If the lowest ceiling block is a non-terrain
  decoration, the whole ceiling treatment is skipped; a protected
  lantern/chain cell is skipped individually.

### Water crossings

Every column with water at or just below the road block becomes
`scaffolding`: `waterlogged=true` where water is present, `distance=0` when
the block below fully supports it and `distance=1` otherwise, `bottom` per the
block below. The scaffold **is** the crossing's walk surface — it replaces the
road block at the solved height, so a crossing reads as a plank bridge.
Because a scaffold is a full block, a crossing cell whose solved surface is a
half-block slab tops out half a block above it: the accepted cost of a visible
bridge. The three paths (water at the road block / water below / dry) are
counted separately in `/vps stats`.

### Decorations are protected

The writer classifies every block it is about to replace against vanilla's
natural terrain tags. Logs, leaves, plants, crops, vines, furniture, lamps and
any unknown or modded block count as protected decoration: the road block,
support fill, upward clear, kerb, ceiling lining, lantern/chain and railings
all skip that block and leave it in place. Only natural terrain and the mod's
own road materials (mud bricks, smooth sandstone, the default cobblestone
kerb, scaffolding, the dirt-path fill) are cleared, filled or wrapped; the
road materials stay replaceable so the shared-column "low kerb and high
railing" rule still works.

The response is deliberately block-by-block: a tree standing exactly in a road
column keeps its block and the road yields there — the requested "let the
decoration win" behaviour, not a missing-road bug. The skipped blocks are
broken down in the `/vps stats` `feature` line. Debug door/seam signs are the
one exception: they replace whatever they need to be visible.

Street decor and piles use the same rule with exact positions: a decor's real
template blocks (cobblestone/snow bases included) and the blocks a village
pile really placed are protected **by position**, not by a bounding box or a
potential box. The pile is recorded when it places; it is a vanilla feature,
not a vertically movable structure, so it keeps its vanilla position and the
writer yields to its actual blocks.

## The mod writes the road, it does not overwrite it

Vanilla places a village before this mod ever runs: the street template is
dropped onto the real terrain by the vanilla `GravityProcessor`, and the mod
could only rewrite it afterwards. Rewriting left two windows where the
original block survived — the profile 9-10 blocks above the ground, or 7-8
blocks below it — and those columns ended up with **two stacked roads**.

The mod now **stops vanilla from placing the road layer at all** on the
columns it owns: it hooks the placement chain itself, drops a claimed column's
vanilla road block before it is ever written, and places the road block once,
itself. Columns the mod does not claim — the height probe failed, or the cell
hit the per-village cap — keep their vanilla road, so a failed probe can never
leave a hole in the world. Non-village structures and disabled worlds see no
behaviour change at all.

`/vps stats` prints both sides (`dropped`, `writes`, `delta`, `yielded to
buildings`), which is how you confirm in game that the two are reconciling. A
positive delta is not automatically a hole: seats, plazas, fixture bases and
ground overlaps are deliberately not plain road writes and are counted in
their own lines. Digging under a bridge deck shows no leftover dirt path.

## Zombie villages are excluded as a whole

Zombie villages (`minecraft:village/<style>/zombie/...`) are deliberately
**excluded from road smoothing as a whole**. The mod detects the `/zombie/`
template path on the structure start and returns before solving: it does not
claim a road mask, does not suppress the vanilla y=0 street layer, does not
move buildings and does not run the writer. The generated zombie village is
exactly vanilla. This is not a per-cell degradation: if any piece of the start
belongs to a zombie template, the entire start is left alone.

## How it works

Vanilla streets use the `terrain_matching` projection: a `GravityProcessor`
drops every template column onto the heightmap independently, which is why the
road jumps a full block whenever the terrain does.

This mod runs a **T1.5 forward decision** while the village pieces are
assembled (inside `Structure#generate`, before the bounding box is frozen):

1. The **road mask** is rebuilt from the street piece templates — never from
   scanning the world, because the vanilla street processor randomly punches
   grass holes into the road. The mask is the street surface connected to the
   y=1 street jigsaw connectors; farmland, water and terracotta inside a
   street template stay vanilla, while a connector base that is plain dirt
   still counts.
2. The **noise terrain** is probed — a pure function of x/z, so the decision
   is deterministic.
3. The mask is decomposed into thin **arms** (exact slice DP) and wide
   **regions** (platform init plus row/column iterative minimisation), and a
   height profile is solved where adjacent cells differ by at most half a
   block. There is **no reference profile**: deviation is measured against raw
   terrain — each column's probed walk surface. Candidate values come from a
   hull around two legal chase lines over that terrain plus the column's own
   probed height (the terrain chase band), and in the final phase the band
   extends toward the region's seam bound along the legal-slope cone, so a
   legal ramp is always reachable and an outlier direction becomes a body
   deviation instead of a full-height seam cliff. Water-crossing columns carry
   the water surface as their preferred height, not a fixed anchor: a smoother
   profile may leave the water level, and every such cell is counted
   (`water anchor deviations`).
4. Every movable rigid component is shifted onto the solved profile at its
   main door — jigsaw junctions synced, world height clamped, overlaps with
   **other buildings** guarded (streets and terminators are not obstacles).
   The road is then re-solved once against the moved footprints, a second
   authoritative alignment fixes the components to that final network, and the
   road is frozen. The solution is cached on the structure start.

At feature placement the current chunk's road cells are rewritten with slabs,
fills, cuts, kerbs, railings and scaffolding, and a diagnostic guard counts
floating/buried buildings. With `roadShapeStage=T5` the mod falls back to
solving at feature placement with the real heightmap and no building move.

## Supported versions

| Minecraft | Fabric Loader | Java | Notes |
|---|---|---|---|
| 1.19.4 | ≥ 0.15.0 | 17 | |
| 1.20.1 | ≥ 0.15.0 | 17 | |
| 1.21.1 | ≥ 0.15.0 | 21 | |
| 1.21.10 | ≥ 0.15.0 | 21 | |
| 1.21.11 | ≥ 0.15.0 | 21 | primary target |
| 26.3 | ≥ 0.15.0 | 25 | unobfuscated build |

No other mods are required. ModMenu is supported (a config screen appears when
it is installed) but optional.

## Configuration

`config/village_path_smoothed.json`, reloaded with `/vps config reload`
(op permission level). Main options:

| Option | Default | Meaning |
|---|---|---|
| `enabled` (global) | `true` | Seed default only: worlds created while the create-screen toggle was untouched start with this. Every world's own switch lives in `<world>/village_path_smoothed.json` and overrides it; change in game with `/vps world on\|off`. |
| `roadSmoothing.objective` | `SMOOTH_FIRST` | `SMOOTH_FIRST` ranks cliff penalty > total steps > deviation from the terrain; `MIN_DEVIATION` weighs deviation and steps together; `SHORTEST_CATCHUP` hugs the terrain and catches up as fast as legal steps allow; `HYBRID` blends both (`hybridWeightDeviation`). |
| `roadSmoothing.roadShapeStage` | `T1_5` | `T1_5` solves the profile and shifts buildings during structure assembly; `T5` is the legacy feature-placement solve with no building move (fallback / A-B comparison). |
| `roadSmoothing.maxBuildingShift` | `0` | Cap on the building component vertical shift in blocks (`0` = unlimited, only the world height clamps). |
| `roadSmoothing.buildingShiftMedianWingBlocks` | `0` | Optional two-wing limit: `> 0` clamps the chosen shift within that many whole blocks of the legacy median scheme's shift (`0` = off). |
| `roadSmoothing.buildingGuard` | `true` | Diagnostic only: count floating/buried moved components in `/vps stats`; never repairs, never reverts. |
| `roadSmoothing.beardCompensation` | `0` | Legacy: blocks added to the noise probe next to buildings. The +1 step compensated the beard but put doorways on a 1-block bump real heightmap columns do not have — keep 0, raise only for A-B comparisons (`< 0` or `0` disables, in both kernel modes). |
| `roadSmoothing.beardRadius` | `4` | Legacy: horizontal radius of that beard zone around building footprints (step kernel mode only). |
| `roadSmoothing.beardKernel` | `step` | Shape of the beard compensation: `step` = flat +1 inside `beardRadius`; `beard_thin` = the real vanilla closed form (cuts above the building base plane, fills below) — experimental, not calibrated. |
| `roadSmoothing.beardKernelBlocksPerDensity` | `1.0` | `beard_thin` only: surface blocks per unit of vanilla beard density; not calibrated yet. |
| `roadSmoothing.allowCutIntoTerrain` | `true` | Lets the road dig into slopes (sunken roads). |
| `roadSmoothing.stepViolationPenalty` | `32` | Soft-constraint penalty per half block of excess drop (cliff escape hatch); the residual is counted in `/vps stats`. |
| `roadSmoothing.dpWindowHalfUnits` | `16` | Per-cell candidate half window in half blocks (auto-shrunk for wide slices). |
| `roadSmoothing.maxExactSectionWidth` | `4` | Slice width solved by the exact DP; wider areas use iterative minimisation. |
| `roadSmoothing.roadSurfaceDetection` | `TEMPLATE_Y0_SOLID` | Street surface rule: `TEMPLATE_Y0_SOLID` = the y0 surface connected to the street jigsaw connectors (farmland/water/terracotta stay vanilla); `TEMPLATE_Y0_ROAD_MATERIAL` = only the style's own road block (connector cells still join). |
| `roadSmoothing.maxFillLayers` | `3` | Max consecutive fill layers when the road sits above the ground; beyond it the cell becomes a bridge deck (`< 0` = unlimited). Writer-side only: it decides how an already-fixed road height is put out (fill vs deck) and never influences the solve. |
| `roadSmoothing.maxClearLayers` | `4` | Max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and a ceiling is formed (`< 0` = unlimited). Writer-side only, same as `maxFillLayers`. The default 4 keeps the cap-formed ceiling and its lighting visible. |
| `kerb.minDropHeight` | `1` | "Side terrain at least N+1 whole blocks above the road" triggers a kerb (walk surfaces compared in half-block units). |
| `kerb.replaceableBlocks` | `["#minecraft:dirt"]` | Legacy/compatibility list; the kerb's per-layer trigger and replacement now use the collision/fluid rule and no longer restrict the replaced material, but they still skip every non-terrain decoration. |
| `waterCrossing.enabled` | `true` | Scaffolding bridges; disable to keep the vanilla road over water. |
| `debug.doorSigns` | `false` | Opt-in debug door signs (main door → road cell, solved surface, actual floor, `NO FRONT`). |
| `debug.seamSigns` | `false` | Opt-in debug seam signs (one per solved-mask seam, with the residual cause when the half-block guarantee breaks). |
| `diagnostics.dumpRoadNetwork` | `false` | Log one line per solved village. |

## Commands

- `/vps world on|off|show` — enable, disable or inspect road smoothing for the
  world the executor is in (per-world switch).
- `/vps config show|reload` — print or reload the config.
- `/vps stats` — villages solved, average solve time, blocks placed; probe and
  write diagnostics (including the probe caliber boundary switches and the
  mismatch columns the alignment pass rewrote); solver residual diagnostics
  (candidate contradictions, residual step violations and their worst step,
  water anchor deviations); the vanilla-block suppression/reconciliation
  counters (`dropped`, `writes`, `delta`, `yielded to buildings`, `zombie
  placements excluded`); the `feature` line (protected decoration blocks kept,
  broken down by road/fill/clear/kerb/ceiling/railing); the `decor` line
  (identified and plan-A excluded decor pieces, actual blocks, base groups and
  cells, settled pieces, protected writes, pile blocks, pin cells, seat-sync
  and road-mismatch counters, isolated seats and resolve failures); and
  villages that degenerated into a single region.
- `/vps dump [radius]` — rebuild and re-solve the road network of villages
  near you and print cell/arm/region counts (diagnostics).
- `/vps debug villagerbrain freeze on|off` — freeze villagers so you can
  inspect generated roads. The state is saved to the global config and applies
  from the next tick; the freeze state and skip count also appear at the end
  of `/vps stats`.

## Known issues

1. **Scaffolding bridges decay.** `ScaffoldingBlock` re-computes its
   `distance` on the first tick after placement, so unsupported bridge spans
   pop off exactly like vanilla scaffolding would. Placing is faithful to the
   requirement; adding support pillars or suppressing the re-check is left for
   a later version.
2. **A water crossing can step by half a block.** The scaffold is the
   crossing's walk surface and a full block, so a crossing cell whose solved
   surface is a slab tops out half a block above it — the accepted cost of a
   visible bridge over a hidden support block.
3. **Desert villages have no water-crossing rule.** Vanilla never converts
   desert roads over water, so there is nothing to convert; the sandstone road
   keeps its vanilla height there.
4. **Kerbs wrap terrain, never decorations.** Sand walls, snow layers and
   other natural terrain are wrapped, and the first cell outward is filled
   even when it was air. Protected building cells and all non-terrain
   decorations are never replaced; a decoration in a probed side column breaks
   the kerb run and is skipped. Because the writer skips only the decoration
   block, a tree or other feature standing in a road/fill/clear column can
   leave a gap there — the requested "let the decoration win" behaviour, not a
   missing-road bug. The tunnel light only appears on straight 3-wide road
   middle cells, never at corners/junctions, and needs a ceiling (raise
   `maxClearLayers` above 0 to see the cap-formed case); a protected
   decoration as the lowest ceiling block suppresses the lining and the
   lantern/chain entirely.
5. **Cliffs get a vanilla-style jump.** Where the terrain drops too fast for
   half steps, the road takes the shortest jump allowed by the soft constraint
   instead of building giant landfills. The residual is counted in
   `/vps stats` (`step violations`), never silent.
6. **Buildings may float or sink.** "Buildings follow the road" means a
   building on a slope is lifted or lowered with the road; on extreme terrain
   a component can end up floating a few blocks or buried (`/vps stats`
   counts them, `buildingGuard`). This is accepted by design (the road
   profile wins); set `roadShapeStage=T5` to disable the shift entirely.
7. **Long grades** are carried by fill/cut up to `maxFillLayers` /
   `maxClearLayers` (bridge decks / lined tunnels). A cell beyond even that
   **keeps its solved height** and is rendered as a deck or a tunnel; it is
   only counted (`fallback re-anchors`) — the writer never corrects the
   solver. A non-zero count therefore means the probe or solver was off for
   those columns: over carvers, and everywhere outside the ±1 chunk
   real-heightmap window (the T1.5 path probes pure noise), the noise height
   can be wrong by many blocks, and those cells come out as floating decks or
   open cuts by design until the probe error is addressed.
8. **The per-world switch only affects chunks generated after it changes**: an
   enable/disable boundary leaves one side vanilla and the other smoothed, and
   already-generated villages are never rewritten. A claimed column whose road
   write fails falls back to a road block on the real surface, so a bug there
   shows up as `surface fallbacks` in `/vps stats` rather than as a hole.
9. **The per-world settings file is runtime-only** and not exercised by the
   automated tests; if the world folder is not writable the file is never
   created and the world simply follows the global default on every start.
10. **The beard compensation kernel is still the legacy `step` approximation.**
    The real `beard_thin` closed form is implemented and unit tested, but its
    density-to-blocks factor is not calibrated against a real world, so the
    default stays on `step`; until it is, a residual ±1 around buildings is
    possible.
11. **A split road mask is solved in parts.** Vanilla routes some side-streets
    through houses, which are not in the mask, so one village can have several
    separate street clusters. Each cluster is solved and written on its own
    and the cells between them keep their vanilla blocks (`split masks` in
    `/vps stats`).
12. **Street scenery is deliberately not kerbed.** Farmland, water channels
    and terracotta trim inside a street template are not road; a road cut
    beside a field can show a raw edge by design. An elevated road still gets
    its drop railing over them; at the scenery's own level nothing is placed.
    Only fixed-shape `*_farm_*` house pieces are rewritten at the solved
    height. Decor and piles are protected by their actual blocks; a
    multi-block decor's pin can still force a counted step where the
    surrounding free road cannot meet the rigid base (`decor` line in
    `/vps stats`).
13. **A feature on a seat keeps its vanilla height.** Hay piles, trees and
    flowers are never moved; if the smoothed seat under one rises or falls,
    the feature can float or sink into the seat. The writer still protects its
    actual blocks, so the road leaves a gap there instead of overwriting it.

## Credits

- [fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)
  (Fallen_Breath, LGPL-3.0) — build skeleton and multi-version preprocessor
  setup; only the build approach was referenced, no code was copied.
- [ModMenu](https://github.com/TerraformersMC/ModMenu) (TerraformersMC, MIT) —
  config screen API.
- Minecraft deobfuscated sources were read for vanilla behaviour research
  only; mappings copyright their respective owners (Mojang / Microsoft).
- Inspiration: Improved Village Placement, CTOV, Lios Overhauled Villages
  (reference only, no code taken).

## License

[WTFPL v2](https://www.wtfpl.net/) — do what the fuck you want to.

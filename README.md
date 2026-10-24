# Village Path Smoothed / 村路平滑化

**English** | [中文](#中文)

Rewrites vanilla village road generation: the "one block jump per column" dirt
paths become continuous roads built with **slab steps**, so neither the forward
nor the lateral direction ever drops more than **half a block**. Roads that
climb slopes sink into the hillside cleanly (with stone kerbs), roads over
water use scaffolding, and dangerous edges get leaf railings.

在**世界生成阶段**改写原版村庄道路：把"逐列贴地、落差 1 格"的土路换成
**台阶（底部半砖 slab，非楼梯）**搭成的连续路面——顺向和横向都不再出现超过
**半格**的落差；爬坡时路面以"下沉路段"的方式切入山坡并由石质包边收壁；
过水处铺脚手架，临崖处种 persistent 树叶护栏。

- Server-side worldgen mod: install it on the server (or in singleplayer),
  clients do **not** need it. / 服务端世界生成 mod：装在服务端或单人游戏即可，客户端无需安装。
- **Create World toggle / 创建世界界面开关** — the Create World screen shows a
  "Village Path Smoothed: Enabled" switch. Pressing "Create" binds the toggle
  value to the world being created (matched by world name): that world's own
  switch is written to `<world>/village_path_smoothed.json` on its first chunk
  generation. Existing worlds and worlds created without touching the toggle
  keep their own setting / fall back to the global default. The per-world
  switch is authoritative; the global config only seeds new worlds. /
  创建世界界面左下角有"村路平滑化：启用"开关：点击"创建"时，开关值绑定到正在
  创建的世界（按世界名匹配），该世界首次生成区块时把开关写入自己的
  `<世界>/village_path_smoothed.json`。已有世界、以及创建时没动过开关的世界，
  沿用各自设置或全局默认值。**世界开关优先**，全局配置只负责播种新世界。
- Works at world creation only (like vanilla worldgen); existing chunks keep
  their vanilla roads. / 只在生成新区块时生效，已生成的区块不会回改。

## Features / 功能

1. **Road smoothing / 路面平滑化** — the road mask is rebuilt from the street
   templates of every village style, then a per-column profile is solved:
   - forward and lateral steps are at most half a block (`|ΔP| ≤ 1` in half units);
   - full-block `dirt_path` road cells are written as **mud bricks**; the
     half-step slabs stay **mud brick slabs**, and the support fill below the
     road stays the vanilla **dirt path**. The desert `smooth_sandstone` road and
     slab are unchanged. Only the mod-written road surface changes: dirt paths
     in plazas, street decor, buildings and the fill under the road are not
     retextured;
   - the road mask is the **connected street surface**: the y0 cells reachable
     from the street jigsaw connectors (plus the decor seats) are road.
     Farmland, water channels and terracotta trim inside a street template are
     scenery, not road: they keep their vanilla block and the writer builds no
     kerb into them. A road that passes **several blocks above** the scenery
     still gets its drop railing on that edge (2026-10-24); at the scenery's
     own level nothing changes. A connector base that is plain dirt still counts,
     so a crossroad seam keeps its column;
   - the default objective (`SMOOTH_FIRST`) ranks *cliff penalty > total step
     count > deviation from the terrain* — so "smooth" means "as few half-block
     steps as possible": short bumps are levelled wherever the candidate band
     allows it, sustained grades are followed at half-block pace (see
     "Terrain chase bands" below);
   - continuous 1-block-per-cell slopes produce *sunken roads*: the path cuts
     into the slope at half-block pace and the kerbs (below) hold the walls;
   - long grades that cannot be carried by fill/cut become **bridge decks**
     (fill cap) or **lined tunnels** (clear cap) instead of being shattered
     into per-cell steps; cliffs that are geometrically impossible for
     half-steps get one short, vanilla-style jump (soft-constraint penalty,
     counted in `/vps stats`).
2. **Terrain chase bands / 地形追线候选带** — per-column candidates come from a
   hull around two legal chase lines over the terrain plus the column's own
   probed height, so on sustained slopes the band follows the terrain instead
   of running out of room. There is **no separate reference profile** any more
   (reverted, fix plan v2 R1): the cost model measures deviation from raw
   terrain (each column's probed walk surface), and where the road must leave
   the terrain the seam-band extension toward the region keeps a legal ramp
   reachable.
3. **Buildings follow the road / 建筑随路** — before the village's bounding box
   is frozen, each movable rigid component is shifted vertically so its
   **vanilla base walk level** (`main piece minY + groundLevelDelta`) meets the
   road at its **main door** — the real door nearest the template's
   `building_entrance` jigsaw. Only blocks in `#minecraft:doors` count as doors
   (tall grass/ferns no longer do), and a double door keeps both leaves. The
   selected road cell is outside every protected building footprint, is not a
   decor seat, and lies on the outward side of the door wall; if there is no
   such cell the component is not moved (`NO FRONT` on its debug sign, when
   `debug.doorSigns` is enabled). The
   shift is applied to the whole rigid component at once and its jigsaw
   junctions are synced, so bearding stays around the building; the shift is a
   pure function of the seed. After the buildings move, the road is re-solved
   once against the moved footprints, then a second authoritative alignment
   aligns the components to that final network and the road is frozen.
   Floating/buried components are only counted (`/vps stats`), never repaired
   (`buildingGuard`).

   **Plan A protects the real building body, not the template bounding box**
   (2026-10-23): the protected cells are the building blocks the template
   really renders plus interior air that meets a building block along at least
   five of its six axes before leaving the template (a normal room's centre
   counts; an open edge does not). The empty part of the old bbox - a courtyard,
   the empty corner of a template, the air under an eave - no longer blocks a
   road block, support fill or clear. There is no Y-direction margin. If a
   template cannot be read, the piece falls back to its whole bounding box, so
   an unknown/modded building never becomes unprotected.

   **Street decor** is part of this building pass, not a plan-A bbox obstacle:
   the writer protects the decor's **actual template blocks** (including its
   cobblestone/snow bases) and still writes the road and the support fill
   below/around them, so a 3×3 lamp no longer leaves holes under its empty bbox
   columns. This applies to **every recognised decor piece**, including a house
   lamp that contributes only a pinned fixture base: the plan-A obstacle list
   excludes it as well, so its 8/2/1 empty ring columns are still paved. A
   template/pool read failure no longer falls back to the whole bbox either; the
   piece is excluded from plan A and its readable blocks are protected
   (`resolve failed` in `/vps stats`). At T5 a multi-block decor pins its **full base footprint** (seat,
   actual blocks and the air columns over road) flat at `baseWalkY`; at T1.5
   that same footprint is one **movable flat unit** in the final solve, and the
   decor is moved onto the level it settles on. The base is therefore part of
   the solve (the user's "fixed shape, vertically movable, even surface"),
   not a frozen anchor. A 1×1 **RIGID** decor piece (a lamp) still rides the
   seat exactly like before. **Every `FeaturePoolElement` - the hay pile, trees
   and flowers - is placed by vanilla and is not a vertical structure**
   (2026-10-23): it never rides a seat, never pins one and is never moved. A
   pile's really placed blocks are still protected by the writer, so the road
   yields to it block by block. Fixture connections follow the 2026-10-21
   semantics: a decor is
   connected only to a **horizontally (4-)adjacent solve cell** outside its own
   base footprint (seat counts as a cell), and every such neighbour is checked
   against the half-block rule; a piece with no external neighbour is counted
   isolated. The decor's base group is the **4-connected component** of its
   footprint that contains the seat/actual base cells; a disconnected bbox ring
   is a different structure and cannot pull the decor off its seat. Vanilla can
   leave the lowest decor block (the child jigsaw's final base) air at a street
   seat; the writer restores exactly that cell (`base restored` in `/vps
   stats`), which closes the lamp-base gap without moving the piece. Decor used
   outside a street seat (a house lamp) is a **pinned non-road fixture base
   cell**: the solver re-solves the four surrounding roads to the base within
   half a block (`fixture base cells` in `/vps stats`), which is what makes a
   corner lamp base smooth when its west road is half a block lower and the
   other three are one block higher.
   Diagonal/lawn seats are therefore isolated, not height-linked.
4. **Structure-ground overlaps / 结构地面与道路重叠** — when a road cell and a
   structure ground claim the same column, the vanilla y=0 block of the claimed
   column is suppressed. Only a **street fixture base** and an original
   **fixed-shape farm house** (`village/<style>/[zombie/]houses/*farm*`, RIGID;
   its whole y=0 ground including farmland/water/dirt paths) take part. The
   scattered farmland inside savanna street templates is **excluded** and stays
   street scenery. The overlap cells of one source structure form one **flat,
   vertically movable unit** in the solve, starting from the structure ground's
   height. The writer renders that structure ground at the solved level instead
   of a road, and moves the template's above-ground blocks (crops, the fixture
   base) with the same whole-block delta, so both roads and the structure
   surface still meet. On T1.5 a fixture base may move as a whole; on T5 the
   fixture base is physically fixed (the farm ground is writer-rendered and
   still follows the solve). `fixture overlap cells`, `farm overlap cells` and
   `ground overlap written (units moved, blocks moved)` in `/vps stats` are the
   observation set.
5. **Kerbs, kerb railings and the ceiling / 路肩包边、包边护栏与包顶** — each
   side is probed **from the road block's own height**, and the two kerb columns
   are probed together ("探2包1"):
   - the **kerb wall** probes the first **and** the second cell outward, but
     still wraps only the first. The old drop threshold is kept
     (`kerb.minDropHeight`), and the highest block is re-read at write time as
     "collision shape non-empty or fluid". At every layer where either of the
     two probed cells has such a block, the first cell gets the village-style
     stone (plains/snowy/taiga `cobblestone`, desert `smooth_sandstone`, savanna
      `terracotta`). An air first cell is filled up to the probed top, so a
      one-block setback becomes a retaining wall; the material is no longer
      limited to the dirt tag. Building blocks and every non-terrain decoration
      (logs, leaves, plants, crops, vines, furniture, lamps, modded blocks) are
      never replaced; the kerb skips the decoration layer and breaks the run
      there. A decorated side column therefore stops the wall instead of
      climbing the tree. **The probe now also checks the building protection**
      (2026-10-24): a protected building cell - the building body or its
      interior air, never the whole bbox - in **either** probed column
      interrupts the run at that layer, so the wall no longer passes through
      building layers and a house wall in the second column no longer drives
      it. A wall whose **second layer is vacant at both probed cells** (the far
      cell's decorations count as "not found") is not built at all, so the
      single-block wall a slab road's 1.5-block rise used to produce is gone.
      When the road has a ceiling (a cap-formed roof or a
      natural cave roof), the wall stops one block below it, so the ceiling
      layer stays road-only and the kerb never climbs the hillside above the
      tunnel; open sky keeps the old wrap-to-side-top behaviour;
   - the **kerb railing** puts one persistent-leaf layer on top of every
     contiguous wrapped run - the natural terrain top and every mid-column
     air-gap top - never into a building and, since 2026-10-24, never into the
     3×3 in front of a movable building's door (the drop railing's doorway
     rule); a run artificially cut off at the
     road's ceiling or the world edge gets no cutoff-top leaves;
   - the **plaza interior / well mouth** is never dressed: a side that points
     into a `town_centers` piece's own y=0 footprint (the well/fountain water and
     jigsaw holes, which are not road cells) gets no kerb or railing. The
     footprint holds the template's **real y=0 blocks only**: vanilla stores air
     as a palette entry too, and since 2026-10-24 those empty corners are
     excluded, so an elevated road crossing them gets its normal kerb and
     railing back (the air corners of `taiga_meeting_point_1` used to blank a
     whole ring around the plaza). The kerb used to wrap the central well's
     water column and plug the well shut; the plaza's outer boundary is
     unaffected;
   - the **kerb end cap** closes the wall's along-road end where the road keeps
     going. The end is judged on the **next side cell alone** (the sky-visible
     cell): it is open when there is no terrain above the current wall top
      (`skyFacts.topmostY() <= wallTop`). The cap is written on the **wall's own
      end column**, and turns upward from `wallTop + 1` with the same
      **probe-2-wrap-1** rule as a normal kerb: it wraps every layer up to the
      probed terrain top of that column and its outward neighbour, with the leaf
      railing on top of each wrapped run. It is never written into the next road
      cell's side column, which is that cell's own drop-railing target. It never
      re-fills the column from the road, an end wall across the road gets no
      sideways column, and an actual road end (no next road cell) is untouched;
   - the **drop railing** (the original leaf railing): scan **down** to the first
     standable ground. When the road is **at least 2 whole blocks** above it,
     persistent leaves (plains `oak`, desert/savanna `acacia`, snowy/taiga
     `spruce`) are placed on the road edge at the road block's height plus one;
     a protected decoration in that target cell is skipped instead. A street
     scenery column (farmland, channel water, terracotta) is judged on the same
     scan, so an elevated road over it is railed too - only the kerb stays
     suppressed toward scenery (2026-10-24);
   - the **ceiling lining** (包顶) still probes only the road's own column, now
     with the same collision/fluid rule. The clear end is re-read at write time
     from the real column (up to the clear cap, default 4), and **only the
     lowest block of the ceiling is lined** - the roof thickness above it is
     left untouched, except at a **ceiling end**: when any 4-adjacent **road**
     cell (along the road or laterally) sees open sky above its own walk
     surface, the **ceiling's end turns upward into a kerb face in the road
     column**, from one above the ceiling layer to that column's terrain top,
     with a leaf railing at each run top - and, since 2026-10-24, **even when
     the upward wrap is zero blocks** (no terrain above the ceiling in the
     column, or the first layer already protected) the end still attempts its
     leaf at the first layer above the ceiling, under every protection. A
     building cell in either probed column interrupts the face like anywhere
     else (2026-10-24). The open cell must still be part of
     the road net - an off-road open cell already carries the road's own side
     kerb, and wrapping there would stack a kerb on top of that kerb. The
     normal probe-2-wrap-1 rule applies: the first cell is the road/face
     column, the second is one cell **into the hill** (opposite the open cell,
     along the road or laterally), never the open cell itself. A layer where
     both probed cells are air stays air; the wall end cap uses the same probe
     rule and cannot place floating cobblestone. When the cap forms the
     ceiling, that bottom block becomes
     **glowstone** on the middle cell of a straight 3-wide road; a natural cave
     roof beyond the cap has its lowest block lined with the kerb material and
     instead gets a hanging **lantern** in the clear-cap cell, connected to the
     roof by an **iron chain**. Side cells, non-3-wide roads, corners and
     junctions get no light. If the lowest ceiling block is a non-terrain
     decoration, the whole ceiling treatment is skipped, and a protected
     lantern/chain cell is skipped individually.

   Every road cell dresses its own edges: a column shared by a low and a high
   road gets the low road's kerb AND the high road's railing (2026-10-15).
6. **Directional terrain probes / 双向地形探测** — the fill/cut decision is made
   per cell too, never from the heightmap top: a **downward** probe finds the
   ground the road is built up from (fill versus bridge deck), while the clear
   end is re-read at write time as the highest block with a collision or a fluid
   (still clamped by `maxClearLayers`). A canopy or a floating layer above an
   open road therefore no longer forces a fill, and an overhang above a solid one
   no longer forces a cut. Neither the clear nor the kerb replaces a protected
   building block or a non-terrain decoration. The raw heightmap is still read
   for the model-vs-terrain deviation statistics and the floating/buried guard,
   which are observations
   only.
7. **Scaffolding water crossings / 过水段脚手架** — every column with water at or
   just below the road block becomes `scaffolding` with `waterlogged=true` when
   water is present, `distance=0` when the block below fully supports it and
   `distance=1` otherwise, `bottom` per the block below. The scaffold **is the
   crossing's walk surface**: it replaces the road block at the solved height, so
   a crossing reads as a plank bridge. Because a scaffold is a full block, a
   crossing cell whose solved surface is a half-block (slab) tops out half a block
   above it — that half step is the accepted cost of a visible bridge. The three
   paths (water at the road block / water below / dry) are counted separately in
   `/vps stats`.
8. **Non-terrain decorations are never touched / 非地形装饰一律保留** — the writer
   classifies every block it is about to replace against vanilla's natural
   terrain tags. Logs, leaves, plants, crops, vines, furniture, lamps and any
   unknown/modded block count as protected decoration: the road block, support
   fill, upward clear, kerb, ceiling lining, lantern/chain and railings skip
   that block and leave it in place. Only natural terrain and the mod's own road
   structural materials (mud bricks, smooth sandstone, the default cobblestone
   kerb, scaffolding, the dirt-path fill) are cleared, filled or wrapped; the
   road materials stay replaceable so the shared-column "low kerb AND high
   railing" rule still works. The response is deliberately block-by-block ("只跳过"), so a tree
   standing exactly in a road column keeps its block and the road yields there;
   the skipped blocks are broken down in the `/vps stats` `feature` line. Debug
   door/seam signs are the one exception - they replace whatever they need to be
   visible.
   **Street decor and piles use the same rule with exact positions:** a decor's
   real template blocks (cobblestone/snow included) and the blocks a village
   pile really placed are protected by position, not by a bounding box or the
   5×5/7×7 potential box. The pile is recorded when it places; the old
   whole-bbox plan-A skip for decor and the hay 1×1 box are gone, so the road
   and its support fill run under/around the decoration. The pile is a vanilla
   feature, not a vertically movable structure: it keeps its vanilla position
   and the writer yields to its actual blocks.

## The mod writes the road; it does not overwrite it

Vanilla places a village before this mod ever runs: the street template is
dropped onto the real terrain by the vanilla `GravityProcessor`, and the mod
could only rewrite it afterwards. Rewriting left two windows where the original
block survived — the profile 9-10 blocks above the ground, or 7-8 blocks below
it — and those columns ended up with **two stacked roads**.

The mod now **stops vanilla from placing the road layer at all** on the columns
it owns. It hooks the placement chain itself: a claimed column's vanilla road
block is dropped before it is ever written, and the road block is placed once,
by the mod. Columns the mod does not claim — the height probe failed, or the
cell hit the per-village cap — keep their vanilla road, so a failed probe can
never leave a hole in the world. Non-village structures and disabled worlds see
no behaviour change at all.

`/vps stats` prints both sides (`dropped`, `writes`, `delta`, `yielded to
buildings`), which is how you confirm in game that the two are reconciling. A
positive delta is not automatically a hole: seats, plazas, fixture bases and
ground overlaps are deliberately not plain road writes and are counted in their
own lines. Digging under a bridge deck shows no leftover `dirt_path`.

## Zombie villages are excluded as a whole / 僵尸村庄整村排除

Zombie villages (`minecraft:village/<style>/zombie/...`) are deliberately
**excluded from road smoothing as a whole**. The mod detects the `/zombie/`
template path on the structure start and returns before solving, so it does not
claim a road mask, does not suppress the vanilla y=0 street layer, does not move
buildings and does not run the writer. The generated zombie village is therefore
exactly vanilla. This is not a per-cell degradation: if any piece of the start
belongs to a zombie template, the entire start is left alone.

僵尸村庄（`minecraft:village/<style>/zombie/...`）**整村排除**：模组在结构开始处
检测模板路径里的 `/zombie/`，在求解前直接返回，因此不会占路网、不会抑制原版
y=0 街道层、不会移动建筑，也不会执行 writer。生成的僵尸村庄与原版完全一致；
只要该 start 的任意一个 piece 属于 zombie 模板，整个 start 都不处理。

## Supported versions / 支持版本

| Minecraft | Fabric Loader | Java | Notes |
|---|---|---|---|
| 1.19.4 | ≥ 0.15.0 | 17 | |
| 1.20.1 | ≥ 0.15.0 | 17 | |
| 1.21.1 | ≥ 0.15.0 | 21 | |
| 1.21.10 | ≥ 0.15.0 | 21 | |
| 1.21.11 (main) | ≥ 0.15.0 | 21 | primary target / 主版本 |
| 26.3 | ≥ 0.15.0 | 25 | unobfuscated build / 免重映射 |

No other mods are required. ModMenu is supported (config screen appears when
ModMenu is installed) but optional. / 无必装依赖；装有 ModMenu 时会出现配置屏幕。

## Config / 配置

`config/village_path_smoothed.json`, reloaded with `/vps config reload`
(op permission level). Highlights / 主要项：

| Option | Default | Meaning |
|---|---|---|
| `enabled` (global) | `true` | seed default only - worlds created while the create-screen toggle was untouched start with this; every world's own switch lives in `<world>/village_path_smoothed.json` and overrides it, change in-game with `/vps world on\|off` |
| `roadSmoothing.objective` | `SMOOTH_FIRST` | `SMOOTH_FIRST` = strict lexicographic *cliff penalty > total steps > deviation from the terrain*; `MIN_DEVIATION` weighs deviation and steps together; `SHORTEST_CATCHUP` hugs the terrain and catches up as fast as legal steps allow; `HYBRID` blends both (`hybridWeightDeviation`) |
| `roadSmoothing.roadShapeStage` | `T1_5` | `T1_5` solves the profile (and shifts buildings) during structure assembly; `T5` is the legacy feature-placement solve with no building move (fallback / A-B comparison) |
| `roadSmoothing.maxBuildingShift` | `0` | cap on the building component vertical shift in blocks (`0` = unlimited, only the world height clamps; fix plan v2 R7 default) |
| `roadSmoothing.buildingShiftMedianWingBlocks` | `0` | optional median two-wing limit for the building shift: `> 0` clamps the chosen shift (legal-interval or median fallback) within that many whole blocks of the legacy median scheme's shift (`0` = off) |
| `roadSmoothing.buildingGuard` | `true` | diagnostic only: count floating/buried moved components (`/vps stats`); never repairs and never reverts a shift |
| `roadSmoothing.beardCompensation` | `0` | legacy: blocks added to the noise probe next to buildings. Default 0 since fix plan v2 R6: the +1 step compensated the beard but made doorways sit on a 1-block bump that real-heightmap columns do not have. Keep 0; raise only for A-B comparisons (`< 0` or `0` disables, in both kernel modes) |
| `roadSmoothing.beardRadius` | `4` | legacy: horizontal radius of that beard zone around building footprints (step kernel mode only) |
| `roadSmoothing.beardKernel` | `step` | shape of the beard compensation: `step` = flat +1 inside `beardRadius`; `beard_thin` = the real vanilla closed form (cuts above the building base plane, fills below) - experimental until the density-to-blocks factor is calibrated in game |
| `roadSmoothing.beardKernelBlocksPerDensity` | `1.0` | `beard_thin` mode only: surface blocks per unit of vanilla beard density; not calibrated yet (review 3 section 7 T6) |
| `roadSmoothing.allowCutIntoTerrain` | `true` | lets the road dig into slopes (sunken roads) |
| `roadSmoothing.stepViolationPenalty` | `32` | soft-constraint penalty per half block of excess drop (cliff escape hatch); the residual is counted in `/vps stats` |
| `roadSmoothing.dpWindowHalfUnits` | `16` | per-cell candidate half window in half blocks (auto-shrunk for wide slices) |
| `roadSmoothing.maxExactSectionWidth` | `4` | slice width solved by the exact DP; wider areas use iterative minimisation |
| `roadSmoothing.roadSurfaceDetection` | `TEMPLATE_Y0_SOLID` | street surface rule: `TEMPLATE_Y0_SOLID` = the y0 surface connected to the street jigsaw connectors (farmland/water/terracotta stay vanilla); `TEMPLATE_Y0_ROAD_MATERIAL` = only the style's own road block (connector cells still join) |
| `roadSmoothing.maxFillLayers` | `3` | max consecutive fill layers when the road sits above the ground; beyond it the cell becomes a bridge deck instead (`< 0` = unlimited). Writer-side only (fix plan v2 R2): it decides how a road whose height is already fixed is put out (landfill vs bridge deck) and never influences the solve |
| `roadSmoothing.maxClearLayers` | `4` | max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and a ceiling is formed (`< 0` = unlimited clear up to the vanilla surface). Writer-side only, same as `maxFillLayers` (fix plan v2 R2). Default 4 makes the cap-formed ceiling/lighting visible |
| `kerb.minDropHeight` | `1` | "side terrain at least N+1 whole blocks above the road" triggers a kerb (walk surfaces compared in half-block units) |
| `kerb.replaceableBlocks` | `["#minecraft:dirt"]` | legacy/compatibility list; the kerb's per-layer trigger and replacement now use the collision/fluid rule and no longer restrict the replaced material, but they still skip every non-terrain decoration |
| `waterCrossing.enabled` | `true` | scaffolding bridges; disable to keep the vanilla road over water |
| `debug.doorSigns` | `false` | opt-in debug door signs (main door → road cell, solved surface, actual floor, `NO FRONT`) |
| `debug.seamSigns` | `false` | opt-in debug seam signs (one per solved-mask seam, with the residual cause when the half-block guarantee breaks) |
| `diagnostics.dumpRoadNetwork` | `false` | log one line per solved village |

## Commands / 命令

- `/vps world on|off|show` — enable / disable / inspect road smoothing for the world the executor is in (per-world switch)
- `/vps config show|reload` — print / reload the config
- `/vps stats` — villages solved, average solve time, blocks placed, probe/write
  diagnostics (including the probe caliber boundary switches and the mismatch
  columns the alignment pass rewrote), solver residual diagnostics (candidate
  contradictions, residual step violations and their worst step, water anchor
   deviations), the vanilla-block suppression/reconciliation counters
   (`dropped`, `writes`, `delta`, `yielded to buildings`, `zombie placements
   excluded`), the `feature` line
   (protected decoration blocks kept, broken down by road/fill/clear/kerb/
   ceiling/railing), the `decor` line (identified and plan-A excluded decor
   pieces, actual blocks, base
   groups and cells, settled pieces, protected writes, pile blocks, pin cells,
   seat-sync and road-mismatch counters, isolated seats and resolve failures),
   and villages that degenerated into a single region
- `/vps dump [radius]` — rebuild + re-solve the road network of villages near
  you and print cell/arm/region counts (diagnostics)
- `/vps debug villagerbrain freeze on|off` — debug: freeze villagers (they
  stand still while you inspect generated roads); the bare `on|off` forms still
  work for one version and print the new syntax. State is saved to the global
  config and applies from the next tick; the freeze state and skip count also
  appear at the end of `/vps stats`

## How it works / 原理

Vanilla streets use the `terrain_matching` projection: a `GravityProcessor`
drops every template column onto the heightmap independently, which is why the
road jumps a full block whenever the terrain does. This mod runs a **T1.5
forward decision** while the village pieces are assembled (inside
`Structure#generate`, before the bounding box is frozen): it rebuilds the *road
mask* from the street piece templates (never from scanning the world - the
vanilla street processor randomly punches grass holes into the road), probes the
**noise** terrain (a pure function of x/z, so the decision is deterministic),
decomposes the mask into thin **arms** (exact slice DP) and wide **regions**
(platform init + row/column iterative minimisation), solves a height profile
where adjacent cells differ by at most half a block, shifts every movable rigid
component onto the solved profile at its main door (syncing jigsaw junctions,
clamping to the world height and guarding against overlaps with **other
buildings** - streets and terminators are not obstacles), and then re-solves the
road once more so the height probe compensates the buildings' new footprints.
The mask itself is the street surface **connected to the y1 street jigsaw
connectors**: farmland, water and terracotta inside a street template stay
vanilla, while a connector base that is plain dirt still counts.
The second authoritative alignment then runs against that final network and the
road is frozen. The solution is cached on the structure start.

At feature placement the current chunk's road cells are rewritten with slabs,
fills, cuts, kerbs, railings and scaffolding, and a diagnostic guard counts
floating/buried buildings. With `roadShapeStage=T5` the mod falls back to
solving at feature placement with the real heightmap and no building move.

Two things are worth spelling out:

- **The terrain chase band (no reference profile).** The solver measures
  deviation from raw terrain - each column's probed walk surface. Candidate
  values come from a hull around two legal chase lines over that terrain, and
  in phase 3 the band extends toward the region's seam bound along the
  legal-slope cone, so a legal ramp is always reachable and an outlier
  direction becomes a body deviation instead of a full-height seam cliff.
  Water-crossing columns carry the water surface as their preferred height
  (their baseP), not as a fixed anchor: a smoother profile may leave the water
  level, and every such cell is counted (`water anchor deviations`).
- **The mod writes the road rather than overwriting it.** The placement chain
  hook drops the vanilla street layer on every column the mask claims, so the
  road block is placed exactly once and no second road can be left underneath
  it. Unclaimed columns keep the vanilla road.

原版街道用 `terrain_matching` 投影，每列独立贴到高度图上，于是地形一跳路面就
跳。本 mod 在村庄拼装期（`Structure#generate` 内、包围盒冻结前）做一次
**T1.5 前移决策**：从街道模板重建"路面掩膜"（而不是扫描世界里的 `dirt_path`
——原版处理器会随机把路面挖成草方块洞），掩膜只取与 y1 街道 jigsaw 连接点连通的
街面（农田/水渠/陶瓦不进掩膜，保留原版；连接点下的普通 dirt 仍算路），探测**噪声**
地形（x/z 的纯函数，
因此决策确定），把掩膜分解为窄**臂**（精确切片 DP）与宽**域**（平台初始化 +
行列迭代优化），求出相邻格落差 ≤ 半格的剖面，再把每个 RIGID 建筑刚性分量整体
平移到剖面上（同步 jigsaw junction、世界高度钳制、重叠守卫），随后**按平移后的
建筑足迹再解一次路**，让高度探针补偿到建筑真正所在的位置。解缓存到结构实例；
到落位期只重写本区块内的路格（台阶/填方/挖方/包边/护栏/脚手架），并由诊断
守卫统计悬空/深埋建筑。`roadShapeStage=T5` 时退回落位期用真实高度图求解且不平移
建筑。

两点值得展开：

- **地形追线候选带**：求解器量的就是与原地形的偏差（每列探得的行走面）。候选值来自
  两条沿地形的合法追线围成的包络；phase 3 里候选带沿合法坡度锥向区域接缝界延伸，
  合法坡道总是可达，离群方向变成带内偏移而不是整高接缝悬崖。过水列的水面高度是
  首选值（即其 baseP）而非固定锚：更平的解可以偏离水面，偏离会计数
  （`water anchor deviations`）。
- **路是本 mod 写的，不是覆盖出来的**：放置链挂点会丢弃掩膜认领列上的原版街道
  路块，路块只由 writer 落一次，下方不会再残留第二层路；未被认领的列保留原版路。

## Known issues / 已知问题

1. **Scaffolding bridges decay** — `ScaffoldingBlock` re-computes its
   `distance` on the first tick after placement, so unsupported bridge spans
   pop off exactly like vanilla scaffolding would. Placing is faithful to the
   requirement; adding support pillars or suppressing the re-check is left for
   a later version.
2. **A water crossing can step by half a block** — the scaffold is the
   crossing's walk surface and a scaffold is a full block, so a crossing cell
   whose solved surface is a slab (half block) tops out half a block above it.
   That is the accepted cost of a visible bridge over a hidden support block.
3. **Desert villages have no water-crossing rule** — vanilla never replaces
   desert roads with planks over water, so there is nothing to convert; the
   sandstone road is left at its vanilla height there.
4. **Kerbs wrap terrain, never decorations** — sand walls, snow layers and
   other natural terrain are wrapped (the dirt-only rule is gone), and the first
   cell outward is filled even when it was air. Protected building boxes and all
   non-terrain decorations are never replaced; a decoration in the probed side
   column breaks the kerb run and is skipped. Since 2026-10-24 a protected
   building cell in either probed column interrupts the run the same way, and a
   wall whose second layer is vacant at both probed cells is not built at all -
   a low rise next to a slab road that used to get a single kerb block now stays
   bare (the requested look, not a missing edge). Because the writer skips only the
   decoration block, a tree or other feature standing in a road/fill/clear
   column can leave a gap there — that is the requested "let the decoration win"
   behaviour, not a missing-road bug. The ceiling light is only placed on
   straight 3-wide road middle cells, never at a corner/junction, and needs a
   ceiling (raise `maxClearLayers` above 0 to see the cap-formed case); a
   protected decoration as the lowest ceiling block suppresses the lining and
   the lantern/chain entirely.
5. **Cliffs get a vanilla-style jump** — where the terrain drops too fast for
   half steps, the road takes the shortest jump allowed by the soft constraint
   instead of building giant landfills. The residual is counted in
   `/vps stats` (`step violations`), never silent.
6. **Buildings may float or sink** — the whole point of "buildings follow the
   road" is that a building on a slope is lifted or lowered with the road; on
   extreme terrain a component can end up floating a few blocks or buried
   (`/vps stats` counts them, `buildingGuard`). This is accepted by design (the
   road profile wins); set `roadShapeStage=T5` to disable the shift entirely.
7. Long grades are carried by fill/cut up to `maxFillLayers` / `maxClearLayers`
   (bridge decks / lined tunnels). A cell whose solved height is beyond even
   that **keeps its solved height** and is rendered as a deck or a tunnel; it
   is only counted (`fallback re-anchors`, fix plan v2 R3) - the writer never
   corrects the solver. A non-zero count therefore means the probe or solver
   was off for those columns: over carvers, and everywhere outside the
   +/-1 chunk real-heightmap window (the T1.5 path probes pure noise), the
   noise height can be wrong by many blocks, and those cells come out as
   floating decks or open cuts by design (N5) until the probe error is
   addressed.
8. The per-world switch only affects chunks generated after it changes: an
   enable/disable boundary leaves one side vanilla and the other smoothed, and
   already-generated villages are never rewritten. A claimed column whose road
   write fails falls back to a road block on the real surface, so a bug there
   shows up as `surface fallbacks` in `/vps stats` rather than as a hole.
9. The per-world settings file (`<world>/village_path_smoothed.json`) is
   exercised only at runtime, not by the automated tests; if the world folder
   is not writable the file is never created and the world simply follows the
   global default on every start.
10. The beard compensation kernel is still the legacy `step` approximation. The
    real `beard_thin` closed form is implemented and unit tested, but its
    density-to-blocks factor is not calibrated against a real world, so the
    default stays on `step`; until it is, a residual ±1 around buildings is
    possible.
11. **A split road mask is solved in parts** — vanilla routes some side-streets
    through houses, which are not in the mask, so one village can have several
    separate street clusters. Each cluster is solved and written on its own and
    the cells between them keep their vanilla blocks (`split masks` in
    `/vps stats`). Before 2026-10-14 a split mask handed the whole village back
    to vanilla, which made the mod look like it did nothing in real worlds.
12. **Street scenery is deliberately not kerbed** — farmland, water channels
    and terracotta trim inside a street template are not road (decision
    2026-10-20). They get no kerb, so a road cut beside a field can
    show a raw edge by design; since 2026-10-24 an elevated road still gets
    its **drop railing** over them, and at the scenery's own level nothing
    is placed. The 2026-10-22 overlap unit deliberately does
    not take them; only fixed-shape `*_farm_*` house pieces are rewritten at the
    solved height. Decor and piles are
    protected by their actual blocks; a multi-block decor's T5/T1.5 pin can
    still force a counted step where the surrounding free road cannot meet the
    rigid base (`decor` line in `/vps stats`).
13. **A feature on a seat keeps its vanilla height** — hay piles, trees and
    flowers (`FeaturePoolElement`) are never moved (2026-10-23). If the smoothed
    seat under one rises or falls, the feature can float or sink into the seat;
    the writer still protects its actual blocks, so the road leaves a gap there
    instead of overwriting it. That is the requested vanilla placement, not a
    missing-road bug.

## Credits / 致谢

- [fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)
  (Fallen_Breath, LGPL-3.0) — build skeleton and multi-version preprocessor
  setup; only the build approach was referenced, no code was copied.
- [ModMenu](https://github.com/TerraformersMC/ModMenu) (TerraformersMC, MIT) —
  config screen API.
- Minecraft deobfuscated sources were read for vanilla behaviour research only;
  mappings copyright their respective owners (Mojang / Microsoft).
- Inspiration: Improved Village Placement, CTOV, Lios Overhauled Villages
  (reference only, no code taken).

## License

[WTFPL v2](https://www.wtfpl.net/) — do what the fuck you want to.

---

# 中文

**[English](#village-path-smoothed--村路平滑化)** | 中文

见上方双语说明。要点：

- **功能**：路面平滑化（半砖台阶，顺向/横向 ≤ 半格；默认 `SMOOTH_FIRST` 目标函数按
  **断崖 > 总台阶数 > 对地形的偏差** 排序，坡地下沉路段 + 包边收壁，断崖软约束
  兜底且计数）；整格土径路面改铺**泥砖**，半砖仍是泥砖台阶，路面下方垫的土径、
  广场/街件/房屋的土径均不改；**路面掩膜只取与街道 jigsaw 连接点连通的街面**
  （2026-10-20 决策）：savanna 农田/水渠、desert 陶瓦镶边视为街面景物，不进掩膜、
  保留原版方块，writer 也不向其包边；高于景物数格的架空路仍按落差规则放护栏
  （2026-10-24），景物自身高度处不加任何东西；连接点下的普通 dirt 仍算路，接缝不破洞；
  **结构地面与路格重叠**（2026-10-22，修正于同日）：同一列既有路格又有结构地面时，
  被认领列的 vanilla y=0 方块本就被抑制。只有两类来源参与：**街件底座**与
  **原版固定形状农舍件**（`village/<style>/[zombie/]houses/*farm*`，RIGID；整个 y=0
  固定地面，含农田/水渠/土径）。**热带草原街道模板里的散装农田不参与**，仍是 street
  scenery。该来源结构的实际重叠列组成一个**可整体升降的平单元**参与求解，起点取结构
  地面高度。writer 在解出高度写结构地面（不是路面），并按同一整格位移带上模板中地面
  以上的作物/底座等方块，使两侧道路与结构地面仍平滑相接；T1.5 街件底座可整体移动，
  T5 街件底座固定（农舍地面由 writer 重写，仍随求解）。`/vps stats` 的
  `fixture overlap cells`、`farm overlap cells` 与
  `ground overlap written (units moved, blocks moved)` 是观测集；
  地形追线候选带（无独立参考剖面，偏差直接对地形量，
  接缝带向区域侧延伸保证合法坡道可达）；路肩包边（**探2包1**：向外探测两格、只包
  第1格；写入时重读最高碰撞/液体方块，保留侧比路高 >1 格阈值，逐层只要两格中任一格
  有碰撞或液体就包第1格，空气也补成挡土墙，不再限土类，不替换房屋；原木/树叶/花草/
  作物/藤蔓/家具/灯具等**所有非地形装饰**逐格跳过，包边遇到装饰断开而不攀爬；
  **2026-10-24 起探2阶段也检测建筑保护**：探2两格（外1/外2）任一格在建筑保护范围
  （建筑体及内部空气，非整个 bbox）内则该层不包、当前包边段中断并照常在段顶放树叶
  ——包边墙不再穿过建筑层继续，远格的房屋墙也不再驱动包边；**第2格两探格均"空置"
  （按包边探测口径：远格装饰视为没探到，建筑方块不算空置）时整墙不建、第1格也不包**，
  半砖路 1.5 格落差不再产生单格包边墙；有顶板时包边在
  顶板下一格截止，不再沿山坡包到顶板之上；开放天空仍包到侧列探测顶）；**广场件自身的
  y=0 足迹（井口/喷泉水格、jigsaw 格，均非路格）不向里包边**，避免把中心水井糊住，
  广场外边界不受影响；足迹只登记模板**真实的 y=0 方块**——原版把空气也存成正式条目，
  2026-10-24 起空气角不再入足迹，从模板空角上方经过的架空路恢复正常的包边与落差护栏
  （此前 `taiga_meeting_point_1` 的 324 个空气角曾把广场外一整圈架空路的护栏清空）；包边墙沿道路方向（且道路确实继续）的末端，**只按外侧那格（能看到天空的格）判定露天**：
  该格在当前墙顶以上完全无地形时（`skyFacts.topmostY() <= wallTop`）才收口；包边**写在墙端本列**
  （不是外侧那格），从墙顶+1 起按常规“探2包1”向上包到本列与外侧探测列的地形顶端，每段顶端放
  包边护栏。外侧那格是下一道路格自己的落差护栏目标，不再被端帽占用。实际道路尽头（没有下一格路）
  暂不处理；包边护栏
  （每个连续包边段顶端——地形顶端和空隙顶端——放一层树叶，不进入房屋；因顶板或世界
  边界被截断的段不放截断顶叶；2026-10-24 起包边顶树叶同样受**房屋门前 3×3 不设
  护栏**豁免，与落差护栏同规则）；包顶（仍只探测路面本列，改用碰撞/液体判据；清格终点
  写入时重读，默认上限 4；**只处理顶板最低一格**，其上厚度保持原样；**包顶末端例外**
  （2026-10-22，2026-10-23 放宽）：4 邻格中**是路格**的一格（沿路或横向）在其自身路面
  以上见天时，**顶板末端就在路列内向上转成包边**：从包顶层上一格包到该路列地形顶端，
  并在各段顶端放护栏；2026-10-24 起即使向上包 **0 格**（本列顶板上方无地形，或首层
  即被保护中断），也在顶板上一格尝试放树叶，受各项保护限制；探2任一格在建筑保护范围内
  则该面向上包同样中断。露天格必须仍在路网内——离开道路的露天格旁边已有道路自己的侧向
  包边，再向上包就成了“包边上再包边”，不触发。按常规
  **探2包1**：第1格是路列/转上来的面，第2格是**往山体里退一格**（露天格的反方向，沿路
  或横向），**不会探回露天格**；两格都是空气的层不放圆石，墙端收口同样遵守此规则。
  最低格若是非地形装饰则整列跳过、不换材质也不挂灯/链；到达上限形成的
  顶板最低格在三格宽直路中间格换**荧石**，超过上限的自然洞顶最低格保留包边材质并在
  上限格放**灯笼 + 铁链**，路口/拐角/非三格宽不做）；树叶落差护栏（条件不变；每条路各管
  各的边缘，同侧列可同时出现矮路的包边和高路的护栏；街道景物列走同一落差判定，
  架空路从农田/水渠/陶瓦上方经过时也放护栏，包边仍一律不伸入景物，2026-10-24）；过水段脚手架（`distance` 有
  支撑 0 / 无支撑 1，`waterlogged=true`；过水锚点为软约束，偏离水面会被计数）。
  广场（town_centers）进路网并钉定为水平（T1.5 把广场件整体移到解出的高度，写入端
  由原版刚性平台自己呈现，井沿/喷泉原样）；房屋对齐到原版基面
  （`主体件 minY + groundLevelDelta`）与**主门**（离 `building_entrance` 最近的真实门，
  双开门并列；高草/大蕨/侧门/后门/上层门不再参与），门口路格只取房屋外、门墙外侧、
  非座格且写入端不会跳过者；没有合格外侧路时房屋不动，门牌第四行写 `NO FRONT`。
  重解后再做一次权威对齐并冻结道路。位移取门邻路格的合法区间内离 0 最近者；解算结果
  按（维度+结构+锚点区块）跨实例共享。
  **plan A 保护建筑体本身而不是模板 bbox**（2026-10-23）：保护模板真实放置的非空气
  建筑方块，以及沿六个轴向看、至少五个方向在离开模板前能遇到建筑方块的内部空气
  （普通房间中央算内部，开放边缘不算）；bbox 内的空列（院子、模板空角、屋檐下的
  空气）不再阻止路块、垫方和清格，Y 方向也不再额外放宽 1 格。模板读取失败时退回
  整件 bbox，避免无法识别的模组建筑失去保护。
  **街件装饰**按实际方块保护（含圆石/雪底座），plan A 不再整 bbox 跳过，路块与垫方
  仍写在装饰下方/周围；多格装饰在 T5 把 seat + 实际 footprint 钉在 `baseWalkY`，
  T1.5 首轮平移后同样钉住、强制最终重解，纯多格装饰组件不再被二次对齐搬动，座位与
  装饰不脱节；**街件与路网的连接**按 2026-10-21 语义：只认求解方块的水平（4）邻接；
  装饰以自身 base footprint 内的求解格为端点，对 footprint 外的每个 4 邻接格检查高度差
  ≤ 半格，没有外部邻格计为孤立（seat 也算格/邻格）；base group 只取与 seat/实际底座格
  4 连通的那部分 footprint，不连通的外圈不能把装饰拉离座位；原版若把灯座最底层 jigsaw final 方块
  留成空气，writer 只补这一格（`base restored`），不移动装饰、不写道路；街道座位之外的装饰
  （房屋灯座）作为**固定的非道路 fixture base cell** 加入求解（writer 跳过该格），求解器把四周道路
  重解到基座 ±半格（`fixture base cells`）——西侧低半格、另外三侧高 1 格时，调的是道路，不是只补基座；
  **干草堆/树/花等 `FeaturePoolElement` 一律按原版放置**（2026-10-23）：不再随座位升降，
  也不把座位钉在它们的高度；只有 RIGID 单件灯随座位。**pile 在放置时记录真正放下的方块**
  并按位置保护，不再按 5×5/7×7 潜在盒保护，也不再给干草堆单独的 1×1 保护盒。
  清格、路块、填方、包边、包顶、灯笼/铁链和护栏遇到非地形装饰都**逐格跳过**，只处理
  自然地形和本 mod 自己的路面结构（泥砖、平滑砂岩、默认圆石包边、脚手架、土径填方；
  这些保持可替换，以便同侧列“低路包边 + 高路护栏”仍成立）；跳过的方块数按类别显示在
  `/vps stats` 的 `feature` / `decor` 行。调试门牌/缝牌仍然为了可见性替换目标格。
  路面由本 mod **写入**而非覆盖：认领列上的原版街道路块在放置链里被丢弃，未认领列
  保留原版路，因此不会再出现上下两层路。写入端只渲染不解题（R3）：超预算格保留
  解算高度并计数，不再吸附回真实地表。
- **支持版本**：1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11（主）/ 26.3。
- **配置**：`config/village_path_smoothed.json`，`/vps config reload` 热重载；
  ModMenu 提供配置屏幕（可选）。
- **原理**：见上方 "How it works"。
- **已知问题**：脚手架无支撑段会按原版规则衰减；沙漠村过水不处理；包边现已接受
  任意有碰撞/液体方块（沙壁、雪层、土径都会包，第1格为空时补墙），但房屋结构和所有
  非地形装饰都不替换，遇到装饰时包边断开并跳过该格；2026-10-24 起探2任一格探到建筑
  保护方块同样中断包边，且第2格空置时整墙不建——半砖路旁 1 格落差处不再出现单格
  包边墙，属既定观感而非漏包；因此树/装饰正好长在路块、填方或
  清格列时会在那里留下缺格，这是“装饰赢”的既定行为，不是漏写路；街面景物（农田/水渠/
  陶瓦）按 2026-10-20 决策不进路网、不包边，路切过田边时可能露出原始断面；多格装饰的
  T5/T1.5 钉可能在与自由路格衔接处留下计数可见的台阶；包边止于道路顶板下
  一格，开放天空才包到侧列顶；
  包顶只包道路正上方最低一格；最低格是装饰时整列不包顶、不挂灯/链；包顶照明只在
  三格宽直路中间格、且存在顶板时
  出现（`maxClearLayers` 默认 4，调到 0/负数会改变可见性）；断崖处出现与原版一致
  的大台阶（计数可见）；座位上的干草堆/树/花按原版高度放置，若座位被平滑抬高或降低，
  它们可能悬空或陷入座位，writer 仍逐块避让，因此那里可能留下缺格——这是 2026-10-23
  “Feature 按原版放置”的既定行为；`beard_thin` 核的密度-方块换算系数尚未实机标定，
  默认仍用 `step` 核。

## 开源协议

[WTFPL v2](https://www.wtfpl.net/)。

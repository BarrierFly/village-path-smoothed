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
4. **Kerbs, kerb railings and the ceiling / 路肩包边、包边护栏与包顶** — each
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
      climbing the tree. When the road has a ceiling (a cap-formed roof or a
      natural cave roof), the wall stops one block below it, so the ceiling
      layer stays road-only and the kerb never climbs the hillside above the
      tunnel; open sky keeps the old wrap-to-side-top behaviour;
   - the **kerb railing** puts one persistent-leaf layer on top of every
     contiguous wrapped run - the natural terrain top and every mid-column
     air-gap top - never into a building; a run artificially cut off at the
     road's ceiling or the world edge gets no cutoff-top leaves;
   - the **drop railing** (the original leaf railing): scan **down** to the first
     standable ground. When the road is **at least 2 whole blocks** above it,
     persistent leaves (plains `oak`, desert/savanna `acacia`, snowy/taiga
     `spruce`) are placed on the road edge at the road block's height plus one;
     a protected decoration in that target cell is skipped instead;
   - the **ceiling lining** (包顶) still probes only the road's own column, now
     with the same collision/fluid rule. The clear end is re-read at write time
     from the real column (up to the clear cap, default 4), and **only the
     lowest block of the ceiling is lined** - the roof thickness above it is
     left untouched. When the cap forms the ceiling, that bottom block becomes
     **glowstone** on the middle cell of a straight 3-wide road; a natural cave
     roof beyond the cap has its lowest block lined with the kerb material and
     instead gets a hanging **lantern** in the clear-cap cell, connected to the
     roof by an **iron chain**. Side cells, non-3-wide roads, corners and
     junctions get no light. If the lowest ceiling block is a non-terrain
     decoration, the whole ceiling treatment is skipped, and a protected
     lantern/chain cell is skipped individually.

   Every road cell dresses its own edges: a column shared by a low and a high
   road gets the low road's kerb AND the high road's railing (2026-10-15).
5. **Directional terrain probes / 双向地形探测** — the fill/cut decision is made
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
6. **Scaffolding water crossings / 过水段脚手架** — every column with water at or
   just below the road block becomes `scaffolding` with `waterlogged=true` when
   water is present, `distance=0` when the block below fully supports it and
   `distance=1` otherwise, `bottom` per the block below. The scaffold **is the
   crossing's walk surface**: it replaces the road block at the solved height, so
   a crossing reads as a plank bridge. Because a scaffold is a full block, a
   crossing cell whose solved surface is a half-block (slab) tops out half a block
   above it — that half step is the accepted cost of a visible bridge. The three
   paths (water at the road block / water below / dry) are counted separately in
   `/vps stats`.
7. **Non-terrain decorations are never touched / 非地形装饰一律保留** — the writer
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

`/vps stats` counts both sides (`suppressed vanilla road blocks`), which is how
you confirm in game that the two are reconciling: the suppressed count tracks
the written count, and digging under a bridge deck shows no leftover
`dirt_path`.

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
| `roadSmoothing.roadSurfaceDetection` | `TEMPLATE_Y0_SOLID` | `TEMPLATE_Y0_ROAD_MATERIAL` only accepts the style's own road block |
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
   deviations), the vanilla-block suppression counters, the `feature` line
   (protected decoration blocks kept, broken down by road/fill/clear/kerb/
   ceiling/railing), and villages that degenerated into a single region
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
——原版处理器会随机把路面挖成草方块洞），探测**噪声**地形（x/z 的纯函数，
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
   column breaks the kerb run and is skipped. Because the writer skips only the
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
  广场/街件/房屋的土径均不改；地形追线候选带（无独立参考剖面，偏差直接对地形量，
  接缝带向区域侧延伸保证合法坡道可达）；路肩包边（**探2包1**：向外探测两格、只包
  第1格；写入时重读最高碰撞/液体方块，保留侧比路高 >1 格阈值，逐层只要两格中任一格
  有碰撞或液体就包第1格，空气也补成挡土墙，不再限土类，不替换房屋；原木/树叶/花草/
  作物/藤蔓/家具/灯具等**所有非地形装饰**逐格跳过，包边遇到装饰断开而不攀爬；有顶板时包边在
  顶板下一格截止，不再沿山坡包到顶板之上；开放天空仍包到侧列探测顶）；包边护栏
  （每个连续包边段顶端——地形顶端和空隙顶端——放一层树叶，不进入房屋；因顶板或世界
  边界被截断的段不放截断顶叶）；包顶（仍只探测路面本列，改用碰撞/液体判据；清格终点
  写入时重读，默认上限 4；**只处理顶板最低一格**，其上厚度保持原样；最低格若是非地形
  装饰则整列跳过、不换材质也不挂灯/链；到达上限形成的
  顶板最低格在三格宽直路中间格换**荧石**，超过上限的自然洞顶最低格保留包边材质并在
  上限格放**灯笼 + 铁链**，路口/拐角/非三格宽不做）；树叶落差护栏（条件不变；每条路各管
  各的边缘，同侧列可同时出现矮路的包边和高路的护栏）；过水段脚手架（`distance` 有
  支撑 0 / 无支撑 1，`waterlogged=true`；过水锚点为软约束，偏离水面会被计数）。
  广场（town_centers）进路网并钉定为水平（T1.5 把广场件整体移到解出的高度，写入端
  由原版刚性平台自己呈现，井沿/喷泉原样）；房屋对齐到原版基面
  （`主体件 minY + groundLevelDelta`）与**主门**（离 `building_entrance` 最近的真实门，
  双开门并列；高草/大蕨/侧门/后门/上层门不再参与），门口路格只取房屋外、门墙外侧、
  非座格且写入端不会跳过者；没有合格外侧路时房屋不动，门牌第四行写 `NO FRONT`。
  重解后再做一次权威对齐并冻结道路。位移取门邻路格的合法区间内离 0 最近者；解算结果
  按（维度+结构+锚点区块）跨实例共享。
  清格、路块、填方、包边、包顶、灯笼/铁链和护栏遇到非地形装饰都**逐格跳过**，只处理
  自然地形和本 mod 自己的路面结构（泥砖、平滑砂岩、默认圆石包边、脚手架、土径填方；
  这些保持可替换，以便同侧列“低路包边 + 高路护栏”仍成立）；跳过的方块数按类别显示在
  `/vps stats` 的 `feature` 行。调试门牌/缝牌仍然为了可见性替换目标格。
  路面由本 mod **写入**而非覆盖：认领列上的原版街道路块在放置链里被丢弃，未认领列
  保留原版路，因此不会再出现上下两层路。写入端只渲染不解题（R3）：超预算格保留
  解算高度并计数，不再吸附回真实地表。
- **支持版本**：1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11（主）/ 26.3。
- **配置**：`config/village_path_smoothed.json`，`/vps config reload` 热重载；
  ModMenu 提供配置屏幕（可选）。
- **原理**：见上方 "How it works"。
- **已知问题**：脚手架无支撑段会按原版规则衰减；沙漠村过水不处理；包边现已接受
  任意有碰撞/液体方块（沙壁、雪层、土径都会包，第1格为空时补墙），但房屋结构和所有
  非地形装饰都不替换，遇到装饰时包边断开并跳过该格；因此树/装饰正好长在路块、填方或
  清格列时会在那里留下缺格，这是“装饰赢”的既定行为，不是漏写路；包边止于道路顶板下
  一格，开放天空才包到侧列顶；
  包顶只包道路正上方最低一格；最低格是装饰时整列不包顶、不挂灯/链；包顶照明只在
  三格宽直路中间格、且存在顶板时
  出现（`maxClearLayers` 默认 4，调到 0/负数会改变可见性）；断崖处出现与原版一致
  的大台阶（计数可见）；`beard_thin` 核的密度-方块换算系数尚未实机标定，默认仍用
  `step` 核。

## 开源协议

[WTFPL v2](https://www.wtfpl.net/)。

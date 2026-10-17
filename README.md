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
   - `dirt_path` roads use **mud brick slabs**, the desert `smooth_sandstone`
     road uses **smooth sandstone slabs**; slabs are always bottom slabs and
     only appear where a half-step is needed;
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
   such cell the component is not moved (`NO FRONT` on its debug sign). The
   shift is applied to the whole rigid component at once and its jigsaw
   junctions are synced, so bearding stays around the building; the shift is a
   pure function of the seed. After the buildings move, the road is re-solved
   once against the moved footprints, then a second authoritative alignment
   aligns the components to that final network and the road is frozen.
   Floating/buried components are only counted (`/vps stats`), never repaired
   (`buildingGuard`).
4. **Kerbs and leaf railings / 路肩包边与树叶护栏** — each side column is probed
   **from the road block's own height**, in the direction that decides the
   detail:
   - the **kerb wall**: scan **up** from the road block's level, skipping air
     gaps, to the side column's topmost block (its height-spectrum top). When
     that is **at least 2 whole blocks** above the road (`kerb.minDropHeight`,
     strictly more than 1), every block from one above the road block up to that
     topmost block that belongs to the vanilla `#minecraft:dirt` tag is replaced
     with a village-style stone (plains/snowy/taiga `cobblestone`, desert
     `smooth_sandstone`, savanna `terracotta`). A side does **not** have to be
     solid at the road level any more - an open edge with a shelf higher up is
     wrapped too;
   - the **railing**: scan **down** to the first standable ground. When the road
     is **at least 2 whole blocks** above it, persistent leaves (plains `oak`,
     desert/savanna `acacia`, snowy/taiga `spruce`) are placed on the road edge
     at the road block's height plus one.

   Every road cell dresses its own edges: a column shared by a low and a high
   road gets the low road's kerb AND the high road's railing (2026-10-15).
5. **Directional terrain probes / 双向地形探测** — the fill/cut decision is made
   per cell too, never from the heightmap top: a **downward** probe finds the
   ground the road is built up from (fill versus bridge deck), an **upward** probe
   finds the top of the contiguous terrain the road has to clear (open cut versus
   tunnel). A canopy or a floating layer above an open road therefore no longer
   forces a fill, and an overhang above a solid one no longer forces a cut. The
   raw heightmap is still read for the model-vs-terrain deviation statistics and
   the floating/buried guard, which are observations only.
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
| `roadSmoothing.maxClearLayers` | `-1` | max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and the kerb material lines the roof (`< 0` = unlimited clear up to the vanilla surface). Writer-side only, same as `maxFillLayers` (fix plan v2 R2) |
| `kerb.minDropHeight` | `1` | "side terrain at least N+1 whole blocks above the road" triggers a kerb (walk surfaces compared in half-block units) |
| `kerb.replaceableBlocks` | `["#minecraft:dirt"]` | blocks the kerb may replace; add e.g. `minecraft:sand` for desert walls |
| `waterCrossing.enabled` | `true` | scaffolding bridges; disable to keep the vanilla road over water |
| `diagnostics.dumpRoadNetwork` | `false` | log one line per solved village |

## Commands / 命令

- `/vps world on|off|show` — enable / disable / inspect road smoothing for the world the executor is in (per-world switch)
- `/vps config show|reload` — print / reload the config
- `/vps stats` — villages solved, average solve time, blocks placed, probe/write
  diagnostics (including the probe caliber boundary switches and the mismatch
  columns the alignment pass rewrote), solver residual diagnostics (candidate
  contradictions, residual step violations and their worst step, water anchor
  deviations), the vanilla-block suppression counters, and villages that
  degenerated into a single region
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
4. **Kerbs only replace `#minecraft:dirt` blocks** — desert sand walls, snow
   layers and `dirt_path` are not in the tag, so they stay. Add them in
   `kerb.replaceableBlocks` if you want them replaced.
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
  兜底且计数）；地形追线候选带（无独立参考剖面，偏差直接对地形量，接缝带向区域
  侧延伸保证合法坡道可达）；路肩包边（从路块上一层起，越过空腔一直包到侧列最高块，
  土类方块换村庄风格石材，侧比路高 >1 格才触发，不再要求路面处固体）；路面列上方
  的连续顶板整段铺包边材质（不再依赖清理上限）；树叶护栏（条件不变；每条路各管
  各的边缘，同侧列可同时出现矮路的包边和高路的护栏）；过水段脚手架（`distance` 有
  支撑 0 / 无支撑 1，`waterlogged=true`；过水锚点为软约束，偏离水面会被计数）。
  广场（town_centers）进路网并钉定为水平（T1.5 把广场件整体移到解出的高度，写入端
  由原版刚性平台自己呈现，井沿/喷泉原样）；房屋对齐到原版基面
  （`主体件 minY + groundLevelDelta`）与**主门**（离 `building_entrance` 最近的真实门，
  双开门并列；高草/大蕨/侧门/后门/上层门不再参与），门口路格只取房屋外、门墙外侧、
  非座格且写入端不会跳过者；没有合格外侧路时房屋不动，门牌第四行写 `NO FRONT`。
  重解后再做一次权威对齐并冻结道路。位移取门邻路格的合法区间内离 0 最近者；解算结果
  按（维度+结构+锚点区块）跨实例共享。
  路面由本 mod **写入**而非覆盖：认领列上的原版街道路块在放置链里被丢弃，未认领列
  保留原版路，因此不会再出现上下两层路。写入端只渲染不解题（R3）：超预算格保留
  解算高度并计数，不再吸附回真实地表。
- **支持版本**：1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11（主）/ 26.3。
- **配置**：`config/village_path_smoothed.json`，`/vps config reload` 热重载；
  ModMenu 提供配置屏幕（可选）。
- **原理**：见上方 "How it works"。
- **已知问题**：脚手架无支撑段会按原版规则衰减；沙漠村过水不处理；包边只认
  `#minecraft:dirt`（沙壁/雪层不换，可配置追加）；断崖处出现与原版一致的大台阶
  （计数可见）；`beard_thin` 核的密度-方块换算系数尚未实机标定，默认仍用 `step` 核。

## 开源协议

[WTFPL v2](https://www.wtfpl.net/)。

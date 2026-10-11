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
   - smooth-first objective (default `SMOOTH_FIRST`): a strict lexicographic
     order *cliff penalty > total step count > deviation from the noise
     terrain*, so roads stay smooth and pay the fill/cut instead of stepping;
   - continuous 1-block-per-cell slopes produce *sunken roads*: the path cuts
     into the slope at half-block pace and the kerbs (below) hold the walls;
   - long grades that cannot be carried by fill/cut become **bridge decks**
     (fill cap) or **lined tunnels** (clear cap) instead of being shattered
     into per-cell steps; cliffs that are geometrically impossible for
     half-steps get one short, vanilla-style jump (soft-constraint penalty).
2. **Buildings follow the road / 建筑随路** — before the village's bounding box
   is frozen, each RIGID building component is shifted vertically so its base
   meets the median adjacent road walk surface (up to `maxBuildingShift`). The
   shift is applied to the whole rigid component at once and its jigsaw
   junctions are synced, so bearding stays around the building; the shift is a
   pure function of the seed. Floating/buried components are only counted
   (`/vps stats`), never repaired (`buildingGuard`).
3. **Kerbs / 路肩包边** — where the side terrain is **at least 2 whole blocks**
   above the road, the dirt wall on the road side is replaced with a
   village-style stone (plains/snowy/taiga `cobblestone`, desert
   `smooth_sandstone`, savanna `terracotta`) from the road surface all the way
   up to the terrain top, for every block that belongs to the vanilla
   `#minecraft:dirt` tag.
4. **Leaf railings and walls / 树叶护栏与矮墙** — where the terrain is **at
   least 2 whole blocks** below the road, persistent leaves (plains `oak`,
   desert/savanna `acacia`, snowy/taiga `spruce`) are placed on the road edge at
   walk-surface height. Where the road is elevated by `kerb.wallMinDropHeight`
   blocks or more, a kerb-material wall is placed on the side column at road
   level so the edge is not just a row of floating leaves.
5. **Scaffolding water crossings / 过水段脚手架** — vanilla plank bridges (and
   any column with water at or just below the road block) become `scaffolding`
   with `waterlogged=true` when water is present, `distance=0` when the block
   below fully supports it and `distance=1` otherwise, `bottom` per the block
   below. The three paths (water at the road block / water below / dry planks)
   are counted separately in `/vps stats`.

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
| `roadSmoothing.objective` | `SMOOTH_FIRST` | `SMOOTH_FIRST` = strict lexicographic *cliff penalty > total steps > deviation*; `MIN_DEVIATION` weighs deviation and steps together; `SHORTEST_CATCHUP` hugs the terrain and catches up as fast as legal steps allow; `HYBRID` blends both (`hybridWeightDeviation`) |
| `roadSmoothing.roadShapeStage` | `T1_5` | `T1_5` solves the profile (and shifts buildings) during structure assembly; `T5` is the legacy feature-placement solve with no building move (fallback / A-B comparison) |
| `roadSmoothing.maxBuildingShift` | `32` | cap on the building component vertical shift in blocks (`0` = unlimited, only the world height clamps) |
| `roadSmoothing.buildingGuard` | `true` | diagnostic only: count floating/buried moved components (`/vps stats`); never repairs and never reverts a shift |
| `roadSmoothing.beardCompensation` | `1` | legacy: blocks added to the noise probe next to buildings. With `roadShapeStage=T1_5` the buildings follow the road instead, so this is only a residual correction of the noise reference (`< 0` or `0` disables, in both kernel modes) |
| `roadSmoothing.beardRadius` | `4` | legacy: horizontal radius of that beard zone around building footprints (step kernel mode only) |
| `roadSmoothing.beardKernel` | `step` | shape of the beard compensation: `step` = flat +1 inside `beardRadius`; `beard_thin` = the real vanilla closed form (cuts above the building base plane, fills below) - experimental until the density-to-blocks factor is calibrated in game |
| `roadSmoothing.beardKernelBlocksPerDensity` | `1.0` | `beard_thin` mode only: surface blocks per unit of vanilla beard density; not calibrated yet (review 3 section 7 T6) |
| `roadSmoothing.allowCutIntoTerrain` | `true` | lets the road dig into slopes (sunken roads) |
| `roadSmoothing.stepViolationPenalty` | `32` | soft-constraint penalty per half block of excess drop (cliff escape hatch) |
| `roadSmoothing.dpWindowHalfUnits` | `16` | per-cell candidate half window in half blocks (auto-shrunk for wide slices); does NOT affect how much height difference a street/crossing seam can absorb - shrinking it below ~4 freezes the region solver to its seed and can leave shallow interior steps |
| `roadSmoothing.maxExactSectionWidth` | `4` | slice width solved by the exact DP; wider areas use iterative minimisation |
| `roadSmoothing.roadSurfaceDetection` | `TEMPLATE_Y0_SOLID` | `TEMPLATE_Y0_ROAD_MATERIAL` only accepts the style's own road block |
| `roadSmoothing.maxFillLayers` | `8` | max consecutive fill layers when the road sits above the ground; beyond it the cell becomes a bridge deck instead (`< 0` = unlimited) |
| `roadSmoothing.maxClearLayers` | `6` | max layers cleared above the road when it sits below the ground; beyond it the cell tunnels and the kerb material lines the roof (`< 0` = unlimited) |
| `kerb.minDropHeight` | `1` | "side terrain at least N+1 whole blocks above the road" triggers a kerb (walk surfaces compared in half-block units) |
| `kerb.wallMinDropHeight` | `3` | side terrain dropping at least this many blocks below the road gets a kerb-material wall at road level (`0` = always, `< 0` = never) |
| `kerb.replaceableBlocks` | `["#minecraft:dirt"]` | blocks the kerb may replace; add e.g. `minecraft:sand` for desert walls |
| `waterCrossing.enabled` | `true` | scaffolding bridges; disable to keep vanilla planks |
| `diagnostics.dumpRoadNetwork` | `false` | log one line per solved village |

## Commands / 命令

- `/vps world on|off|show` — enable / disable / inspect road smoothing for the world the executor is in (per-world switch)
- `/vps config show|reload` — print / reload the config
- `/vps stats` — villages solved, average solve time, blocks placed, probe/write diagnostics, solver residual diagnostics (candidate contradictions, residual step violations, villages that degenerated into a single region)
- `/vps dump [radius]` — rebuild + re-solve the road network of villages near
  you and print cell/arm/region counts (diagnostics)
- `/vps debug villagerbrain [on|off]` — debug: toggle whether villagers skip
  their brain tick (they stand still while you inspect generated roads); state
  is saved to the global config and applies from the next tick

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
where adjacent cells differ by at most half a block, then shifts every RIGID
building component onto the solved profile (syncing jigsaw junctions, clamping
to the world height and guarding against overlaps). The solution is cached on
the structure start; at feature placement the current chunk's road cells are
rewritten with slabs, fills, cuts, kerbs, railings, walls and scaffolding, and
a diagnostic guard counts floating/buried buildings. With
`roadShapeStage=T5` the mod falls back to solving at feature placement with the
real heightmap and no building move.

原版街道用 `terrain_matching` 投影，每列独立贴到高度图上，于是地形一跳路面就
跳。本 mod 在村庄拼装期（`Structure#generate` 内、包围盒冻结前）做一次
**T1.5 前移决策**：从街道模板重建"路面掩膜"（而不是扫描世界里的 `dirt_path`
——原版处理器会随机把路面挖成草方块洞），探测**噪声**地形（x/z 的纯函数，
因此决策确定），把掩膜分解为窄**臂**（精确切片 DP）与宽**域**（平台初始化 +
行列迭代优化），求出相邻格落差 ≤ 半格的剖面，再把每个 RIGID 建筑刚性分量整体
平移到剖面上（同步 jigsaw junction、世界高度钳制、重叠守卫）。解缓存到结构实例；
到落位期只重写本区块内的路格（台阶/填方/挖方/包边/护栏/矮墙/脚手架），并由诊断
守卫统计悬空/深埋建筑。`roadShapeStage=T5` 时退回落位期用真实高度图求解且不平移
建筑。

## Known issues / 已知问题

1. **Scaffolding bridges decay** — `ScaffoldingBlock` re-computes its
   `distance` on the first tick after placement, so unsupported bridge spans
   pop off exactly like vanilla scaffolding would. Placing is faithful to the
   requirement; adding support pillars or suppressing the re-check is left for
   a later version.
2. **Desert villages have no water-crossing rule** — vanilla never replaces
   desert roads with planks over water, so there is nothing to convert; the
   sandstone road is left at its vanilla height there.
3. **Kerbs only replace `#minecraft:dirt` blocks** — desert sand walls, snow
   layers and `dirt_path` are not in the tag, so they stay. Add them in
   `kerb.replaceableBlocks` if you want them replaced.
4. **Cliffs get a vanilla-style jump** — where the terrain drops too fast for
   half steps, the road takes the shortest jump allowed by the soft constraint
   instead of building giant landfills.
5. **Buildings may float or sink** — the whole point of "buildings follow the
   road" is that a building on a slope is lifted or lowered with the road; on
   extreme terrain a component can end up floating a few blocks or buried
   (`/vps stats` counts them, `buildingGuard`). This is accepted by design (the
   road profile wins); set `roadShapeStage=T5` to disable the shift entirely.
6. Long grades are carried by fill/cut up to `maxFillLayers` / `maxClearLayers`
   (bridge decks / lined tunnels); a cell whose target is beyond even that is
   snapped back onto the real surface with the correct parity (counted as
   `fallback re-anchors`).
7. The per-world switch only affects chunks generated after it changes: an
   enable/disable boundary leaves one side vanilla and the other smoothed, and
   already-generated villages are never rewritten.
8. The per-world settings file (`<world>/village_path_smoothed.json`) is
   exercised only at runtime, not by the automated tests; if the world folder
   is not writable the file is never created and the world simply follows the
   global default on every start.

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

- **功能**：路面平滑化（半砖台阶，顺向/横向 ≤ 半格，默认 `MIN_DEVIATION`
  目标函数，坡地下沉路段 + 包边收壁，断崖软约束兜底）；路肩包边（土类方块
  换村庄风格石材，到地表顶、无上限）；树叶护栏（角点、persistent）；过水段
  脚手架（`distance` 有支撑 0 / 无支撑 1，`waterlogged=true`）。
- **支持版本**：1.19.4 / 1.20.1 / 1.21.1 / 1.21.10 / 1.21.11（主）/ 26.3。
- **配置**：`config/village_path_smoothed.json`，`/vps config reload` 热重载；
  ModMenu 提供配置屏幕（可选）。
- **原理**：见上方 "How it works"。
- **已知问题**：脚手架无支撑段会按原版规则衰减；沙漠村过水不处理；包边只认
  `#minecraft:dirt`（沙壁/雪层不换，可配置追加）；断崖处出现与原版一致的大台阶。

## 开源协议

[WTFPL v2](https://www.wtfpl.net/)。

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
  "Village Path Smoothed: Enabled" switch that flips the mod's `enabled` config
  entry, so a world can be created with the smoothing turned off (or re-enabled)
  without touching the mods list. / 创建世界界面左下角有"村路平滑化：启用"开关，
  切换的是全局配置的 `enabled` 项，无需进出 mods 列表即可控制本世界生成时是否平滑。
- Works at world creation only (like vanilla worldgen); existing chunks keep
  their vanilla roads. / 只在生成新区块时生效，已生成的区块不会回改。

## Features / 功能

1. **Road smoothing / 路面平滑化** — the road mask is rebuilt from the street
   templates of every village style, then a per-column profile is solved:
   - forward and lateral steps are at most half a block (`|ΔP| ≤ 1` in half units);
   - `dirt_path` roads use **mud brick slabs**, the desert `smooth_sandstone`
     road uses **smooth sandstone slabs**; slabs are always bottom slabs and
     only appear where a half-step is needed;
   - terrain-following objective (default `MIN_DEVIATION`): minimises the total
     fill/cut, preferring long gentle grades with few steps;
   - continuous 1-block-per-cell slopes produce *sunken roads*: the path cuts
     into the slope at half-block pace and the kerbs (below) hold the walls;
   - cliffs that are geometrically impossible for half-steps get one short,
     vanilla-style jump instead of a giant landfill (soft-constraint penalty).
2. **Kerbs / 路肩包边** — where the terrain rises more than 1 block above the
   road, the dirt wall on the road side is replaced with a village-style stone
   (plains/snowy/taiga `cobblestone`, desert `smooth_sandstone`,
   savanna `terracotta`) from the road surface all the way up to the terrain
   top, for every block that belongs to the vanilla `#minecraft:dirt` tag.
3. **Leaf railings / 树叶护栏** — where the terrain drops more than 1 block
   below the road, persistent leaves (plains `oak`, desert/savanna `acacia`,
   snowy/taiga `spruce`) are placed on the road edge at walk-surface height, at
   the two corner points of every cross section.
4. **Scaffolding water crossings / 过水段脚手架** — the vanilla plank bridges
   become `scaffolding` with `waterlogged=true`, `distance=0` when the block
   below fully supports it and `distance=1` otherwise, `bottom` per the block
   below, exactly as requested.

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
| `roadSmoothing.objective` | `MIN_DEVIATION` | `SHORTEST_CATCHUP` hugs the terrain and catches up as fast as legal steps allow; `HYBRID` blends both (`hybridWeightDeviation`) |
| `roadSmoothing.allowCutIntoTerrain` | `true` | lets the road dig into slopes (sunken roads) |
| `roadSmoothing.stepViolationPenalty` | `32` | soft-constraint penalty per half block of excess drop (cliff escape hatch) |
| `roadSmoothing.dpWindowHalfUnits` | `16` | per-cell candidate half window in half blocks (auto-shrunk for wide slices) |
| `roadSmoothing.maxExactSectionWidth` | `4` | slice width solved by the exact DP; wider areas use iterative minimisation |
| `roadSmoothing.roadSurfaceDetection` | `TEMPLATE_Y0_SOLID` | `TEMPLATE_Y0_ROAD_MATERIAL` only accepts the style's own road block |
| `kerb.minDropHeight` | `1` | "terrain more than N blocks above the road" triggers a kerb |
| `kerb.replaceableBlocks` | `["#minecraft:dirt"]` | blocks the kerb may replace; add e.g. `minecraft:sand` for desert walls |
| `railing.placement` | `CORNER` | `EVERY_CELL` is reserved |
| `waterCrossing.enabled` | `true` | scaffolding bridges; disable to keep vanilla planks |
| `diagnostics.dumpRoadNetwork` | `false` | log one line per solved village |

## Commands / 命令

- `/vps config show|reload` — print / reload the config
- `/vps stats` — villages solved, average solve time, blocks placed
- `/vps dump [radius]` — rebuild + re-solve the road network of villages near
  you and print cell/arm/region counts (diagnostics)

## How it works / 原理

Vanilla streets use the `terrain_matching` projection: a `GravityProcessor`
drops every template column onto the heightmap independently, which is why the
road jumps a full block whenever the terrain does. This mod hooks
`StructureStart#placeInChunk`, rebuilds the *road mask* from the street piece
templates (never from scanning the world - the vanilla street processor
randomly punches grass holes into the road), probes the terrain (real
heightmaps within ±1 chunks, noise base height elsewhere), decomposes the mask
into thin **arms** (exact slice DP) and wide **regions** (platform init +
row/column iterative minimisation), solves a height profile where adjacent
cells differ by at most half a block, caches the solved profile on the
structure start, and rewrites the cells of the current chunk with slabs, fills,
cuts, kerbs, railings and scaffolding.

原版街道用 `terrain_matching` 投影，每列独立贴到高度图上，于是地形一跳路面就
跳。本 mod 在 `StructureStart#placeInChunk` 末尾介入：从街道模板重建"路面掩膜"
（而不是扫描世界里的 `dirt_path`——原版处理器会随机把路面挖成草方块洞），
探测地形（本区块 ±1 用真实高度图、其余用噪声高度），把掩膜分解为窄**臂**
（精确切片 DP）与宽**域**（平台初始化 + 行列迭代优化），求出相邻格落差 ≤ 半格
的剖面并缓存到结构实例上，最后只把落在本区块内的格子重写为台阶/填方/挖方，
并做包边、护栏与脚手架。

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
5. Heights outside the current chunk's ±1 neighbourhood come from the noise
   generator, so roads can differ from the carved/decorated terrain by a block
   or two in rare places (ravine edges, tree-covered ground); the writer fills
   or cuts the column so the road always stands on solid ground.

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

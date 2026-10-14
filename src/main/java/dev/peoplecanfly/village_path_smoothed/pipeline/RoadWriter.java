/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
	 * Rewrite road cells inside one chunk's writable area (plan sections 5.4-5.6).
 *
 * <p>Slabs are always bottom slabs (no stairs, no orientation), so placement is
 * independent of the piece rotation. Writing uses the vanilla structure flags
 * (UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE): no client updates during worldgen and
 * heightmaps are left alone, exactly like StructureTemplate#placeInWorld.
 *
 * <p>Fix plan v4 D6-b: every column's height comes from {@link RoadWritePolicy}
 * and is the cell's <em>solved</em> surface. A cell that cannot carry it is
 * offered the single-cell surface nudge (AGENTS.md, 2026-10-14) and otherwise
 * refused; a per-cell exception takes the same path instead of silently pinning
 * the column to the terrain.
 */
public final class RoadWriter {
	private static final int WRITE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	/** Aggregated diagnostic: cells whose solved height was outside the world bounds and the nudge was refused. */
	public static final AtomicLong CLAMPED_WRITES = new AtomicLong();
	/**
	 * Fix plan v4 F7 / M1: cells whose written walk surface is not the cell's
	 * solved surface, i.e. the writer decided a height. This is the counter that
	 * makes the reported "different chunks do not match" symptom visible at all:
	 * the mask can be perfectly legal and the world still wrong.
	 */
	public static final AtomicLong OVERRIDDEN_WRITES = new AtomicLong();
	/**
	 * Fix plan v4 D6-b: cells written at the real surface because their solved
	 * height could not be used (out of bounds, or their profile write threw).
	 * Legal only under the AGENTS.md nudge rule - never a step against a
	 * neighbour, never beside a building door - and counted so a spike is visible.
	 */
	public static final AtomicLong NUDGED_WRITES = new AtomicLong();
	/**
	 * Fix plan v4 D6-b: cells the writer refused outright, i.e. it wrote nothing
	 * where a claimed column used to get a road block. Should stay at zero; a
	 * non-zero value means a solved height left the world bounds and the nudge
	 * was not legal either.
	 */
	public static final AtomicLong REFUSED_WRITES = new AtomicLong();
	private static final AtomicLong REFUSED_WARNED = new AtomicLong();
	/**
	 * Fix plan v4 D4-a: side columns beside the road where the lateral drop is
	 * more than one whole block, plus the worst such drop in half-block units.
	 * Observation only - v4 leaves the mask edge unrepaired on purpose (D4-b/c/e
	 * are all blocked), so the cliff stays and this number says how big it is.
	 */
	public static final AtomicLong EDGE_DROP_VIOLATIONS = new AtomicLong();
	public static final AtomicLong EDGE_WORST_DROP = new AtomicLong();
	/**
	 * Aggregated diagnostic: cells whose solved walk surface is beyond any
	 * affordable fill/cut. Observation only since fix plan v2 R3 (decision N7):
	 * the solved height is kept and rendered (bridge deck above the surface,
	 * tunnel/open cut below), never snapped back onto the real surface. A
	 * non-zero value means the probe or the solver was off for those columns.
	 */
	public static final AtomicLong FALLBACK_REANCHORS = new AtomicLong();
	/** Aggregated diagnostic: cells whose fill/cut exceeded the configured cap (bridge deck / tunnel). */
	public static final AtomicLong OVER_BUDGET_CELLS = new AtomicLong();
	private static volatile boolean warnedClampedWrites;
	private static volatile boolean warnedFallbackReanchors;

	private RoadWriter() {
	}

	/**
	 * @param shiftedComponents footprints {minX, maxX, minZ, maxZ, baseY} of the
	 *        RIGID components T1.5 moved, or an empty array. They decide whether a
	 *        cell may take the single-cell surface nudge (never at a building door)
	 */
	public static void write(RoadNetwork net, VpsGenContext ctx, BoundingBox chunkBB, VillagePathSmoothedConfig config,
			int[][] shiftedComponents) {
		RoadMaterialTable table = RoadMaterialTable.create(config);
		List<Block> kerbExtras = resolveKerbExtras(config);
		// Review 4 section 9.3: with the vanilla street layer suppressed on every
		// claimed column, the kerb/railing work is the only thing left that two
		// road cells at different heights can both do to the same side column -
		// a low cell lining it with kerb and a high cell railing above. Deciding
		// per chunk which cell owns each side column keeps exactly one writer,
		// and the owner is the highest road, because that is the one whose edge
		// actually has to be held up.
		Map<Long, Integer> sideColumnOwner = highestRoadPerSideColumn(net, chunkBB);
		for (RoadCell cell : net.cells) {
			if (cell.x < chunkBB.minX() || cell.x > chunkBB.maxX() || cell.z < chunkBB.minZ() || cell.z > chunkBB.maxZ()) {
				continue; // written when this cell's own chunk generates
			}
			try {
				writeCell(net, cell, ctx, config, table, kerbExtras, sideColumnOwner, shiftedComponents);
			} catch (Throwable t) {
				VillagePathSmoothed.LOGGER.debug("[VPS] failed to write road cell {} {}", cell.x, cell.z, t);
				// Review 4 risk R3: the cell's vanilla block is NOT there to fall
				// back on any more (it was suppressed), so a per-cell failure has
				// to leave something behind itself. Fix plan v4 D6-b replaces the
				// unconditional surface pin with the same guarded nudge every other
				// fallback uses: it is the only height that is trustworthy at write
				// time, and it is only used when it cannot step.
				writeSurfaceFallback(net, cell, ctx, config, table, shiftedComponents);
			}
		}
	}

	/**
	 * Highest road walk surface (half units) claiming each side column of this
	 * chunk, keyed by the packed x/z of the column.
	 */
	private static Map<Long, Integer> highestRoadPerSideColumn(RoadNetwork net, BoundingBox chunkBB) {
		Map<Long, Integer> owner = new HashMap<>();
		for (RoadCell cell : net.cells) {
			if (cell.x < chunkBB.minX() || cell.x > chunkBB.maxX() || cell.z < chunkBB.minZ() || cell.z > chunkBB.maxZ()) {
				continue;
			}
			int road2 = RoadWritePolicy.roadWalkSurface2(cell.solvedP / 2 - 1, (cell.solvedP & 1) != 0);
			for (int dir = 0; dir < 4; dir++) {
				int outX = cell.x + SIDE_DX[dir];
				int outZ = cell.z + SIDE_DZ[dir];
				if (net.cellAt(outX, outZ) >= 0) {
					continue; // road continues this way: no side edge
				}
				long key = RoadCell.packedXZ(outX, outZ);
				Integer current = owner.get(key);
				if (current == null || road2 > current) {
					owner.put(key, road2);
				}
			}
		}
		return owner;
	}

	/**
	 * Last-resort road block when the profile write threw.
	 *
	 * <p>Deliberately the simplest possible column: no slab parity games, no
	 * kerbs, no railings. The height is the real surface, but it is no longer
	 * taken unconditionally - fix plan v4 D6-b routes it through the same
	 * {@link RoadWritePolicy#surfaceNudgeColumn} guard as every other fallback,
	 * so a cell whose write threw cannot become a step against its neighbours
	 * either. It is refused (and counted) when the nudge is not legal, which is
	 * the one case where a claimed column has no road block at all.
	 */
	private static void writeSurfaceFallback(RoadNetwork net, RoadCell cell, VpsGenContext ctx,
			VillagePathSmoothedConfig config, RoadMaterialTable table, int[][] shiftedComponents) {
		try {
			int topY = ctx.realSurfaceTopY(cell.x, cell.z);
			RoadWritePolicy.Column column = RoadWritePolicy.surfaceNudgeColumn(cell.solvedP, topY,
					ctx.getMinY(), ctx.getMaxY(), neighbourSurfaces(net, cell),
					nearBuildingEntrance(shiftedComponents, cell.x, cell.z));
			if (!column.written()) {
				REFUSED_WRITES.incrementAndGet();
				warnRefused(cell, topY);
				return;
			}
			Block road = table.forStyle(cell.style).roadBlock;
			Block configured = "STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)
					? null
					: VpsCompat.blockById(config.roadSmoothing.fillMaterial);
			if (configured != null) {
				road = configured;
			}
			setBlock(ctx, cell.x, RoadWritePolicy.roadBlockY(column.walkSurface2(), column.slab()), cell.z,
					road.defaultBlockState());
			VpsPipeline.SURFACE_FALLBACK_WRITES.incrementAndGet();
			NUDGED_WRITES.incrementAndGet();
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] surface fallback failed at {} {}", cell.x, cell.z, t);
		}
	}

	private static final int[] SIDE_DX = {-1, 1, 0, 0};
	private static final int[] SIDE_DZ = {0, 0, -1, 1};

	private static void writeCell(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable table, List<Block> kerbExtras, Map<Long, Integer> sideColumnOwner, int[][] shiftedComponents) {
		int x = cell.x;
		int z = cell.z;
		int topY = ctx.realSurfaceTopY(x, z);
		RoadMaterialTable.StyleMaterials materials = table.forStyle(cell.style);
		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();

		// ---- water crossing: scaffolding instead of the vanilla planks (5.6)
		int wt = topY - 1; // vanilla puts the planks at the water surface
		boolean waterAtWt = config.waterCrossing.enabled && isWater(ctx, x, wt, z);
		boolean waterBelow = config.waterCrossing.enabled && isWater(ctx, x, wt - 1, z);
		boolean crossing = config.waterCrossing.enabled && WaterCrossings.isCrossing(waterAtWt, waterBelow);
		if (crossing) {
			if (waterAtWt) {
				VpsPipeline.SCAFFOLD_WATER_SELF.incrementAndGet();
			} else if (waterBelow) {
				VpsPipeline.SCAFFOLD_WATER_BELOW.incrementAndGet();
			} else {
				VpsPipeline.SCAFFOLD_DRY.incrementAndGet();
			}
		}

		// ---- fix plan v4 D6-b: the writer never decides a height on its own.
		// The column's walk surface is the cell's solved surface, water crossing or
		// not; the scaffolding of a crossing is placed UNDER the profile road block
		// (RoadWritePolicy#scaffoldSupportY), because a full block cannot reproduce a
		// half-block walk surface without stepping against one of the neighbours.
		// A cell that cannot carry its solved height (outside the dimension bounds)
		// or whose profile write threw is offered the single-cell surface nudge, and
		// refused outright when that would step.
		RoadWritePolicy.Column column = RoadWritePolicy.profileColumn(cell.solvedP, minY, maxY);
		if (!column.written()) {
			column = RoadWritePolicy.surfaceNudgeColumn(cell.solvedP, topY, minY, maxY,
					neighbourSurfaces(net, cell), nearBuildingEntrance(shiftedComponents, x, z));
			if (column.written()) {
				NUDGED_WRITES.incrementAndGet();
			} else {
				// the old behaviour wrote the clamped height; keeping the counter so a
				// regression to "clamp and write" is still visible in /vps stats
				CLAMPED_WRITES.incrementAndGet();
				REFUSED_WRITES.incrementAndGet();
				warnRefused(cell, topY);
				return;
			}
		}
		if (column.overrodeSolved()) {
			OVERRIDDEN_WRITES.incrementAndGet();
		}
		boolean slab = column.slab();
		int roadTopBlockY = RoadWritePolicy.roadBlockY(column.walkSurface2(), slab);

		// ---- over-budget observation (fix plan v2 R3, decision N7): the writer
		// is a renderer, not a second solver. A cell whose solved walk surface
		// is beyond every affordable fill/cut is COUNTED and warned about, and
		// the solved height is written as-is: above the surface the fill cap
		// renders it as a bridge deck, below it the clear cap decides tunnel
		// vs open cut. The old snap onto the real surface is gone - it
		// manufactured a |dP| >= 2 step against the neighbouring solved cells
		// (exactly the drop class N1 forbids) to hide a probe error that this
		// counter now exposes instead.
		if (config.roadSmoothing.enabled
				&& RoadWritePolicy.overBudget(cell.solvedP, topY, config.roadSmoothing.maxFillLayers, config.roadSmoothing.maxClearLayers)) {
			FALLBACK_REANCHORS.incrementAndGet();
			if (!warnedFallbackReanchors) {
				warnedFallbackReanchors = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} solved walk surface {} is beyond the fill/cut budget (real surface {}); kept the solved height - observation, not corrected; a non-zero count means the probe or solver was off", x, z, cell.solvedP / 2.0, topY);
			}
		}

		if (crossing) {
			// the crossing column is written like any other (profile, not a bare
			// scaffold), then the scaffolding goes in as the support directly under
			// the road block - the block vanilla would have used as the plank deck
			placeScaffolding(ctx, x, z,
					RoadWritePolicy.scaffoldSupportY(crossing, topY, roadTopBlockY),
					isWater(ctx, x, RoadWritePolicy.scaffoldSupportY(crossing, topY, roadTopBlockY), z), config);
			VpsPipeline.SCAFFOLDS_PLACED.incrementAndGet();
		}

		if (config.roadSmoothing.enabled) {
			Block roadBlock = materials.roadBlock;
			if ("STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)) {
				// support columns use the style's road material
			} else {
				Block configured = VpsCompat.blockById(config.roadSmoothing.fillMaterial);
				if (configured != null) {
					roadBlock = configured;
				}
			}

			// fill support below the road block up to the vanilla surface; when
			// the fill exceeds maxFillLayers the column becomes a bridge deck
			// instead (whole-block road: the single road block; slab road: the
			// road slab rides on an upside-down top slab of the same material).
			// The deck stays at the SOLVED height so the half-step constraint to
			// neighbouring cells is preserved
			int fillLayers = roadTopBlockY - topY;
			if (RoadWritePolicy.bridgeDeck(fillLayers, config.roadSmoothing.maxFillLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
				if (slab) {
					setBlock(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
							.defaultBlockState()
							.setValue(SlabBlock.TYPE, SlabType.TOP));
				}
				BlockState roadState = slab
						? materials.slabBlock.defaultBlockState()
						: roadBlock.defaultBlockState();
				setBlock(ctx, x, roadTopBlockY, z, roadState);
				VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();
				applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY, slab, sideColumnOwner);
				return;
			}
			fillSupport(ctx, x, z, topY, roadTopBlockY, materials, config);
			// road block itself (replaces the vanilla road block / grass patch hole)
			BlockState roadState = slab
					? materials.slabBlock.defaultBlockState()
					: roadBlock.defaultBlockState();
			setBlock(ctx, x, roadTopBlockY, z, roadState);
			// clear whatever the vanilla generation left above the road block
			// (old road block, grass patch holes, planks) - this also restores the
			// grass patch holes (Q12) because the road block is written over them;
			// with a clear cap the remaining terrain becomes a tunnel roof lined
			// with the kerb material
			clearAbove(ctx, x, z, roadTopBlockY, topY, config);
			if (RoadWritePolicy.tunnelRoof(roadTopBlockY, topY, RoadWritePolicy.clearTopY(roadTopBlockY, topY, config.roadSmoothing.maxClearLayers), config.roadSmoothing.maxClearLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
				// line the tunnel roof with the style's kerb material (report:
				// "包边覆盖隧道顶"; the side kerb below keeps replacing #dirt only)
				setBlock(ctx, x, RoadWritePolicy.clearTopY(roadTopBlockY, topY, config.roadSmoothing.maxClearLayers) + 1, z, materials.kerbBlock.defaultBlockState());
			}
		}
		VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();

		// ---- kerbs and railings on side edges (5.5)
		applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY, slab, sideColumnOwner);
	}

	/** Support fill between the real surface and the road block (never above the surface). */
	private static void fillSupport(VpsGenContext ctx, int x, int z, int topY, int roadTopBlockY,
			RoadMaterialTable.StyleMaterials materials, VillagePathSmoothedConfig config) {
		Block roadBlock = materials.roadBlock;
		if (!"STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)) {
			Block configured = VpsCompat.blockById(config.roadSmoothing.fillMaterial);
			if (configured != null) {
				roadBlock = configured;
			}
		}
		for (int y = topY - 1; y < roadTopBlockY; y++) {
			setBlock(ctx, x, y, z, roadBlock.defaultBlockState());
		}
	}

	/** Clears the terrain left above the road block, up to the clear cap. */
	private static void clearAbove(VpsGenContext ctx, int x, int z, int roadTopBlockY, int topY, VillagePathSmoothedConfig config) {
		int clearTop = RoadWritePolicy.clearTopY(roadTopBlockY, topY, config.roadSmoothing.maxClearLayers);
		for (int y = roadTopBlockY + 1; y <= clearTop; y++) {
			setBlock(ctx, x, y, z, Blocks.AIR.defaultBlockState());
		}
	}

	/** Walk surfaces of the cell's road neighbours, as they will be written. */
	private static int[] neighbourSurfaces(RoadNetwork net, RoadCell cell) {
		int[] out = new int[4];
		int n = 0;
		for (int dir = 0; dir < 4; dir++) {
			int j = net.neighbors[cell.index][dir];
			if (j >= 0) {
				out[n++] = net.cells.get(j).solvedP;
			}
		}
		return n == 0 ? null : java.util.Arrays.copyOf(out, n);
	}

	/**
	 * Fix plan v4 D6-b + AGENTS.md (2026-10-14): a cell beside a moved building is
	 * never nudged onto the real surface, so a nudged cell cannot pull a doorway
	 * away from the road. "Beside" means within one block of the component's
	 * footprint, which is the only entrance geometry available at write time.
	 */
	private static boolean nearBuildingEntrance(int[][] shiftedComponents, int x, int z) {
		if (shiftedComponents == null) {
			return false;
		}
		for (int[] component : shiftedComponents) {
			if (x >= component[0] - 1 && x <= component[1] + 1 && z >= component[2] - 1 && z <= component[3] + 1) {
				return true;
			}
		}
		return false;
	}

	private static void warnRefused(RoadCell cell, int topY) {
		REFUSED_WARNED.incrementAndGet();
		if (REFUSED_WARNED.get() > 4) {
			return; // one WARN per village is enough; the counter carries the rest
		}
		VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} cannot be written: solved walk surface {} is outside the world and the single-cell surface nudge ({}) would step against a neighbour or sit at a building door; left it unwritten",
				cell.x, cell.z, cell.solvedP / 2.0, topY);
	}

	private static void applyKerbsAndRailings(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras, int roadTopBlockY, boolean slab,
			Map<Long, Integer> sideColumnOwner) {
		// walk surfaces in half-block units: a slab walk surface is 0.5 above
		// the block Y, a whole block is 1.0 (A1: the old code compared
		// "roadTopBlockY + 1" which is 0.5 too high for slabs and off by one
		// whole block for the kerb/railing thresholds)
		int sRoad2 = RoadWritePolicy.roadWalkSurface2(roadTopBlockY, slab);
		for (int dir = 0; dir < 4; dir++) {
			int outX = cell.x + SIDE_DX[dir];
			int outZ = cell.z + SIDE_DZ[dir];
			if (net.cellAt(outX, outZ) >= 0) {
				continue; // road continues this way: no side edge
			}
			// review 4 section 7 (P2): one writer per side column. A shared
			// column used to get a kerb from the lower road cell and a railing
			// from the higher one, which is the "kerb and railing on the same
			// spot" complaint; the highest road owns the column now.
			Integer owner = sideColumnOwner.get(RoadCell.packedXZ(outX, outZ));
			if (owner != null && owner != sRoad2) {
				continue;
			}
			int outTopY;
			try {
				outTopY = ctx.realSurfaceTopY(outX, outZ); // +/-1 chunk of the current chunk: readable
			} catch (Throwable t) {
				continue;
			}
			int sSide2 = 2 * outTopY;
			// fix plan v4 D4-a: measure the mask-edge drop once per side column
			// (this branch only runs for the column's owner, see sideColumnOwner)
			int lateralDrop = Math.abs(sSide2 - sRoad2);
			if (lateralDrop > 2) {
				EDGE_DROP_VIOLATIONS.incrementAndGet();
				EDGE_WORST_DROP.accumulateAndGet(lateralDrop, Math::max);
			}

			if (config.kerb.enabled && RoadWritePolicy.kerbApplies(sSide2, sRoad2, config.kerb.minDropHeight)) {
				placeKerb(ctx, outX, outZ, roadTopBlockY, outTopY, materials, kerbExtras);
			}
			if (config.railing.enabled && RoadWritePolicy.railingApplies(sSide2, sRoad2, config.railing.minDropHeight)) {
				placeRailing(ctx, outX, outZ, roadTopBlockY + 1, materials);
			}
		}
	}

	/** Replaces dirt-tag blocks on the side column from the road block up to the side surface (Q07/Q08: no upper limit). */
	private static void placeKerb(VpsGenContext ctx, int x, int z, int roadTopBlockY, int outTopY,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras) {
		boolean placed = false;
		for (int y = Math.max(ctx.getMinY(), roadTopBlockY); y <= Math.min(ctx.getMaxY(), outTopY - 1); y++) {
			BlockState existing = ctx.blockState(x, y, z);
			if (existing.is(BlockTags.DIRT) || kerbExtras.contains(existing.getBlock())) {
				setBlock(ctx, x, y, z, materials.kerbBlock.defaultBlockState());
				placed = true;
			}
		}
		if (placed) {
			VpsPipeline.KERBS_PLACED.incrementAndGet();
		}
	}

	private static void placeRailing(VpsGenContext ctx, int x, int z, int walkSurfaceY, RoadMaterialTable.StyleMaterials materials) {
		BlockState existing = ctx.blockState(x, walkSurfaceY, z);
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return;
		}
		BlockState leaves = materials.leavesBlock.defaultBlockState();
		if (leaves.hasProperty(LeavesBlock.PERSISTENT)) {
			leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
		}
		setBlock(ctx, x, walkSurfaceY, z, leaves);
		VpsPipeline.RAILINGS_PLACED.incrementAndGet();
	}

	/**
	 * Places the scaffolding block at {@code scaffoldY} - the solved road height
	 * since fix plan v4 D6-b, not the vanilla surface height.
	 *
	 * <p>waterlogged = water at the scaffold block itself; the caller passes that
	 * exact read, not the wider crossing predicate (review 3 F8-1).
	 */
	private static void placeScaffolding(VpsGenContext ctx, int x, int z, int scaffoldY, boolean waterlogged, VillagePathSmoothedConfig config) {
		BlockPos belowPos = new BlockPos(x, scaffoldY - 1, z);
		BlockState below = ctx.blockState(x, scaffoldY - 1, z);
		// vanilla ScaffoldingBlock#getDistance counts a column as supported when
		// the block below has a sturdy UP face - NOT when it is a full collision
		// cube, so slabs or snow-covered grass count as support too; our writer
		// must use the identical criterion or the first tick will recompute a
		// different distance than we placed (bottom-of-village drift)
		boolean supported = below.isFaceSturdy(ctx.level, belowPos, Direction.UP);
		int distance = supported
				? Math.max(0, Math.min(7, config.waterCrossing.distanceWhenSupported))
				: Math.max(0, Math.min(7, config.waterCrossing.distanceWhenUnsupported));
		// vanilla isBottom: distance > 0 && the block below is NOT scaffolding,
		// i.e. a hanging scaffold reads bottom=true (the old check here was
		// inverted: it flagged scaffolds resting on another scaffold)
		boolean bottom = distance > 0 && !below.is(Blocks.SCAFFOLDING);
		BlockState state = Blocks.SCAFFOLDING.defaultBlockState()
				.setValue(ScaffoldingBlock.DISTANCE, distance)
				.setValue(ScaffoldingBlock.BOTTOM, bottom)
				.setValue(ScaffoldingBlock.WATERLOGGED, waterlogged);
		setBlock(ctx, x, scaffoldY, z, state);
	}

	/** True when the column has water at this exact Y (block or fluid state). */
	private static boolean isWater(VpsGenContext ctx, int x, int y, int z) {
		if (ctx.blockState(x, y, z).is(Blocks.WATER)) {
			return true;
		}
		return ctx.level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
	}

	private static void setBlock(VpsGenContext ctx, int x, int y, int z, BlockState state) {
		if (y < ctx.getMinY() || y > ctx.getMaxY()) {
			return;
		}
		ctx.level.setBlock(new BlockPos(x, y, z), state, WRITE_FLAGS);
	}

	private static List<Block> resolveKerbExtras(VillagePathSmoothedConfig config) {
		List<Block> extras = new ArrayList<>();
		for (String entry : config.kerb.replaceableBlocks) {
			if (entry == null) {
				continue;
			}
			String trimmed = entry.trim().toLowerCase();
			if (trimmed.startsWith("#")) {
				if (!trimmed.equals("#minecraft:dirt")) {
					VillagePathSmoothed.LOGGER.debug("[VPS] ignoring unsupported kerb tag {}", entry);
				}
				continue; // the dirt tag itself is always honoured
			}
			Block block = VpsCompat.blockById(trimmed);
			if (block != null) {
				extras.add(block);
			}
		}
		return extras;
	}
}

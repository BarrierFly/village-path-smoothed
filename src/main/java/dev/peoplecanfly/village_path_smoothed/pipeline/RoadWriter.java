/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.terrain.FeatureProtection;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
//#if MC >= 12001
//$$ import net.minecraft.world.level.block.entity.SignText;
//#endif
//#if MC >= 260000
//$$ import net.minecraft.world.level.block.entity.SignTextSlot;
//#endif
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
	/**
	 * Plan A (user decision 2026-10-15): claimed road cells skipped entirely
	 * because their road block would land inside a movable building's 3D box, so
	 * the building body is never overwritten. Non-zero is the count of columns the
	 * road yields to a building.
	 */
	public static final AtomicLong BUILDING_CELLS_SKIPPED = new AtomicLong();
	/**
	 * User decision 2026-10-17: every non-terrain decoration wins its block.
	 * The road block and the support fill yield per block, and the clear, kerb,
	 * ceiling lining, lantern/chain and railings skip the decoration instead of
	 * replacing it. Natural terrain is still cleared/filled normally. A non-zero
	 * count is expected in any village with trees; it is not a defect.
	 */
	public static final AtomicLong FEATURE_ROAD_BLOCKS_SKIPPED = new AtomicLong();
	public static final AtomicLong FEATURE_FILL_BLOCKS_SKIPPED = new AtomicLong();
	public static final AtomicLong FEATURE_CLEAR_BLOCKS_SKIPPED = new AtomicLong();
	public static final AtomicLong FEATURE_KERB_BLOCKS_SKIPPED = new AtomicLong();
	public static final AtomicLong FEATURE_CEILING_BLOCKS_SKIPPED = new AtomicLong();
	public static final AtomicLong FEATURE_RAILING_BLOCKS_SKIPPED = new AtomicLong();
	/**
	 * User decision 2026-10-15: water-crossing cells whose solved surface is
	 * above the water surface. They are rendered as a bridge deck with no support
	 * fill from the waterbed (never a dirt causeway), unlike an at-water crossing
	 * which keeps the scaffold.
	 */
	public static final AtomicLong WATER_BRIDGE_CELLS = new AtomicLong();
	/**
	 * Plan C (user decision 2026-10-15): claimed plaza cells rendered by their
	 * own rigid piece - the writer writes no road block, no support fill and no
	 * upward clear on them (the clear would raze the fountain), and the solved
	 * surface equals the platform's real walk surface by the phase-2 pin.
	 */
	public static final AtomicLong PLAZA_CELLS_VANILLA = new AtomicLong();
	/**
	 * Decor seats written at the solved height (user decision 2026-10-16): the
	 * seat block (grass/sand/...) renders the solved walk surface and the RIGID
	 * decor piece on top was shifted to match at T1.5.
	 */
	public static final AtomicLong SEATS_WRITTEN = new AtomicLong();
	/**
	 * R1-3 (four-symptom fix plan 2026-10-16): seat columns whose normal write
	 * threw and fell back. The fallback now renders the SEAT block (the old code
	 * wrote the road material, silently turning a decor island into road) - a
	 * non-zero count says the seat write path is failing.
	 */
	public static final AtomicLong SEAT_WRITE_FALLBACK = new AtomicLong();
	/**
	 * User decision 2026-10-15 (kerb rework): blocks of the ceiling run directly
	 * above the walk surface that were lined with the kerb material - the whole
	 * contiguous run, no longer only the single block at the clear cap and no
	 * longer gated on a cap being configured at all.
	 */
	public static final AtomicLong ROOF_LINED_BLOCKS = new AtomicLong();
	/**
	 * Kerb railings (user decision 2026-10-17): leaf layers placed on top of a
	 * kerb run, including the top of every run separated by an air gap, but not
	 * where the run was artificially cut off at the world edge.
	 */
	public static final AtomicLong KERB_RAILINGS_PLACED = new AtomicLong();
	/** Ceiling lights on straight 3-wide road middle cells: glowstone at a capped ceiling. */
	public static final AtomicLong CEILING_GLOWSTONE_PLACED = new AtomicLong();
	/** Ceiling lights on straight 3-wide road middle cells: hanging lantern + chain at a natural roof. */
	public static final AtomicLong CEILING_LANTERNS_PLACED = new AtomicLong();
	/** Chain blocks placed between a hanging ceiling lantern and the natural cave roof. */
	public static final AtomicLong CEILING_CHAINS_PLACED = new AtomicLong();
	/** Railings suppressed on a movable building's door front (user decision 2026-10-15). */
	public static final AtomicLong RAILINGS_SKIPPED_AT_DOOR = new AtomicLong();
	/** Railings suppressed beside water because the road is not above the water surface (user decision 2026-10-15). */
	public static final AtomicLong RAILINGS_SKIPPED_AT_WATER = new AtomicLong();
	/**
	 * User decision 2026-10-15: road slabs and leaf railings placed into a water
	 * source block go down WATERLOGGED instead of silently displacing the water.
	 */
	public static final AtomicLong WATERLOGGED_PLACEMENTS = new AtomicLong();
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
			int[][] shiftedComponents, int[][] buildingBoxes, int[][] doorFronts, int[][] doorSigns,
			int[][] seamSigns, String village) {
		RoadMaterialTable table = RoadMaterialTable.create(config);
		// User decision 2026-10-15: every road cell applies its own edge details
		// - the one-owner-per-side-column rule is gone. A shared column now gets
		// the low road's kerb AND the high road's railing, which is the point:
		// each road holds up its own edge. The side-column probes are memoised
		// per (column, road level) so a shared column is scanned once per height,
		// and the drop metric stays once per column.
		Map<SideKey, SideFacts> sideFacts = new HashMap<>();
		Set<Long> countedEdgeColumns = new HashSet<>();
		java.util.BitSet threeWideMiddles = net.threeWideMiddleCells();
		for (RoadCell cell : net.cells) {
			if (cell.x < chunkBB.minX() || cell.x > chunkBB.maxX() || cell.z < chunkBB.minZ() || cell.z > chunkBB.maxZ()) {
				continue; // written when this cell's own chunk generates
			}
			try {
				writeCell(net, cell, ctx, config, table, sideFacts, countedEdgeColumns, shiftedComponents,
						buildingBoxes, doorFronts, threeWideMiddles, village);
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
		// debug door/seam signs run LAST: a road write or a clear in this pass can
		// never take a sign back down. Both are opt-in debug features (default off).
		if (config.debug.doorSigns) {
			writeDoorSigns(ctx, chunkBB, doorSigns, net);
		}
		if (config.debug.seamSigns) {
			writeSeamSigns(ctx, chunkBB, seamSigns);
		}
	}

	/**
	 * Side column of one road cell's edge, keyed by column and road level: the
	 * same column can adjoin roads at different heights and both probes start
	 * from the road block's own Y.
	 */
	private record SideKey(int x, int z, int roadTopBlockY) {
	}

	/**
	 * What the two probes found in one side column, starting from the road
	 * block's own level:
	 *
	 * <ul>
	 *   <li>{@code topmostY} - the highest <b>terrain</b> block at or above the
	 *       road block's level, scanning UP through air gaps to the column's
	 *       height-spectrum top ({@link Integer#MIN_VALUE} when the column is
	 *       empty up there); non-terrain decorations are skipped, so a tree
	 *       canopy is not treated as a wall to wrap;</li>
	 *   <li>{@code groundTopY} - first air above the ground found scanning DOWN
	 *       from the road block's level; the surface a railing guards against
	 *       (an overhang above an open edge must not hide the drop).</li>
	 * </ul>
	 */
	private record SideFacts(int topmostY, int groundTopY) {
	}

	/** Returns the column's facts, or null when the column cannot be read (never fatal). */
	private static SideFacts probeSideFacts(VpsGenContext ctx, int x, int z, int roadTopBlockY) {
		try {
			// User decision 2026-10-17: the kerb criterion is "collision shape
			// non-empty or fluid", re-read at write time. It is no longer a
			// dirt-material test, and the height spectrum is not a stale probe.
			// A later decision the same day excludes non-terrain decorations
			// from the probe, so a tree canopy can no longer pull a retaining
			// wall up to its top.
			int topmost = ctx.highestTerrainCollisionOrFluidY(x, z, roadTopBlockY);
			int ground = ctx.groundSurfaceTopY(x, z, roadTopBlockY);
			return new SideFacts(topmost, ground);
		} catch (Throwable t) {
			return null; // +/-1 chunk of the current chunk: readable, but never fatal
		}
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
		if (cell.seat) {
			writeSeatFallback(cell, ctx);
			return;
		}
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
			if (!setBlockUnlessProtected(ctx, cell.x,
					RoadWritePolicy.roadBlockY(column.walkSurface2(), column.slab()), cell.z,
					road.defaultBlockState(), FEATURE_ROAD_BLOCKS_SKIPPED)) {
				return;
			}
			VpsPipeline.SURFACE_FALLBACK_WRITES.incrementAndGet();
			NUDGED_WRITES.incrementAndGet();
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] surface fallback failed at {} {}", cell.x, cell.z, t);
		}
	}

	/**
	 * R1-3 (four-symptom fix plan 2026-10-16): last-resort render of a decor seat
	 * whose normal write threw. The old catch routed a seat through the road
	 * fallback, which paved the seat island over with road material; the seat
	 * block wins here instead. Deliberately the simplest column: dirt fill, the
	 * seat block at the solved height, no clear (the decor stands above it).
	 */
	private static void writeSeatFallback(RoadCell cell, VpsGenContext ctx) {
		try {
			int seatY = RoadWritePolicy.roadBlockY(cell.solvedP, false);
			Block seatBlock = VpsCompat.blockById(cell.seatBlock);
			if (seatBlock == null) {
				seatBlock = Blocks.GRASS_BLOCK;
			}
			int groundTopY = ctx.groundSurfaceTopY(cell.x, cell.z, seatY);
			for (int y = groundTopY - 1; y < seatY; y++) {
				setBlockUnlessProtected(ctx, cell.x, y, cell.z, Blocks.DIRT.defaultBlockState(),
						FEATURE_FILL_BLOCKS_SKIPPED);
			}
			if (!setBlockUnlessProtected(ctx, cell.x, seatY, cell.z, seatBlock.defaultBlockState(),
					FEATURE_ROAD_BLOCKS_SKIPPED)) {
				return;
			}
			SEAT_WRITE_FALLBACK.incrementAndGet();
			VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] seat fallback failed at {} {}", cell.x, cell.z, t);
		}
	}

	/**
	 * Fix plan v4 R2-3: the model-vs-real deviation (the writer is the only place
	 * where both heights exist), reported and warned about, never corrected.
	 * {@link #DEVIATION_WORST} is in half-block walk-surface units; the average in
	 * {@link #DEVIATION_SUM} / {@link #DEVIATION_CELLS}.
	 */
	public static final AtomicLong DEVIATION_CELLS = new AtomicLong();
	public static final AtomicLong DEVIATION_SUM = new AtomicLong();
	public static final AtomicLong DEVIATION_WORST = new AtomicLong();
	/** Cells whose deviation is past {@code surfaceDeviationWarnBlocks}. */
	public static final AtomicLong DEVIATION_OVER_THRESHOLD_CELLS = new AtomicLong();
	/** Cells written below the real terrain by more than one whole block (no-cut in the world). */
	public static final AtomicLong CUT_BELOW_TERRAIN_CELLS = new AtomicLong();
	/** Cells written above the real terrain by more than one whole block (bridge decks). */
	public static final AtomicLong FILL_ABOVE_TERRAIN_CELLS = new AtomicLong();
	private static final java.util.concurrent.atomic.AtomicBoolean DEVIATION_WARNED =
			new java.util.concurrent.atomic.AtomicBoolean();

	private static final int[] SIDE_DX = {-1, 1, 0, 0};
	private static final int[] SIDE_DZ = {0, 0, -1, 1};

	private static void writeCell(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable table, Map<SideKey, SideFacts> sideFacts,
			Set<Long> countedEdgeColumns, int[][] shiftedComponents,
			int[][] buildingBoxes, int[][] doorFronts, java.util.BitSet threeWideMiddles, String village) {
		int x = cell.x;
		int z = cell.z;

		// ---- plan C (user decision 2026-10-15): the plaza renders itself. The
		// rigid piece's platform IS the solved surface (pinned in phase 2), so
		// the writer only runs the edge details on the plaza's boundary and
		// never writes a road block, a fill or a clear on it - the clear would
		// destroy the fountain, the bell and the well rim riding on the platform.
		if (cell.plaza) {
			PLAZA_CELLS_VANILLA.incrementAndGet();
			RoadMaterialTable.StyleMaterials plazaMaterials = table.forStyle(cell.style);
			applyKerbsAndRailings(net, cell, ctx, config, plazaMaterials,
					RoadWritePolicy.roadTopBlockY(cell.solvedP), cell.solvedP, sideFacts, countedEdgeColumns,
					buildingBoxes, doorFronts, RoadWritePolicy.NO_CEILING);
			return;
		}

		// ---- decor seat (user decision 2026-10-16): the seat RIDES the solved
		// profile - it is a normal solver cell - and renders its vanilla block
		// (grass/sand/...) at the solved height. The RIGID decor piece on top
		// (lamp/tree/hay) was shifted to match at T1.5, so the write must not
		// clear above the old seat block (the decor stands there) and dresses no
		// edges: the island stays vanilla-looking.
		if (cell.seat) {
			SEATS_WRITTEN.incrementAndGet();
			int seatY = RoadWritePolicy.roadBlockY(cell.solvedP, false);
			Block seatBlock = VpsCompat.blockById(cell.seatBlock);
			if (seatBlock == null) {
				seatBlock = Blocks.GRASS_BLOCK;
			}
			int groundTopY = ctx.groundSurfaceTopY(x, z, seatY);
			for (int y = groundTopY - 1; y < seatY; y++) {
				setBlockUnlessProtected(ctx, x, y, z, Blocks.DIRT.defaultBlockState(),
						FEATURE_FILL_BLOCKS_SKIPPED);
			}
			setBlockUnlessProtected(ctx, x, seatY, z, seatBlock.defaultBlockState(),
					FEATURE_ROAD_BLOCKS_SKIPPED);
			int oldSeatY = RoadWritePolicy.roadBlockY(cell.baseP, false);
			for (int y = seatY + 1; y <= oldSeatY; y++) {
				setBlockUnlessProtected(ctx, x, y, z, Blocks.AIR.defaultBlockState(),
						FEATURE_CLEAR_BLOCKS_SKIPPED);
			}
			VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();
			return;
		}

		// ---- plan A (user decision 2026-10-15): a building always wins. A claimed
		// column whose road block would land inside a movable building's 3D box is
		// skipped entirely - no road block, no support fill, no upward clear - so
		// smoothing can never overwrite a building body. Counted so the yield is
		// visible in /vps stats.
		if (config.roadSmoothing.protectBuildings && !cell.seat
				&& RoadWritePolicy.insideBuilding(buildingBoxes, x, z, cell.solvedP)) {
			BUILDING_CELLS_SKIPPED.incrementAndGet();
			return;
		}

		RoadMaterialTable.StyleMaterials materials = table.forStyle(cell.style);
		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();
		// The raw heightmap read now only feeds the observations (the model-vs-real
		// deviation and the refused-write warning) and the crossing predicate. The
		// upward clear end is re-read at write time (user decision 2026-10-17).
		int heightTopY = ctx.realSurfaceTopY(x, z);

		// ---- water crossing. The predicate is unchanged (water at the heightmap
		// surface or one below). What is new (user decision 2026-10-15):
		//   * the crossing surface is never below the water surface - a profile below
		//     it is raised to the water surface;
		//   * AT the water surface the cell keeps its scaffold as before;
		//   * ABOVE the water surface it is always a bridge deck - no scaffold and no
		//     support fill from the waterbed, however shallow the water is.
		// The water-surface walk surface is 2 * heightTopY: the surface block sits at
		// heightTopY - 1 and a full block on it tops out at heightTopY.
		int wt = heightTopY - 1; // vanilla puts the planks at the water surface
		boolean waterAtWt = config.waterCrossing.enabled && isWater(ctx, x, wt, z);
		boolean waterBelow = config.waterCrossing.enabled && isWater(ctx, x, wt - 1, z);
		boolean crossing = config.waterCrossing.enabled && WaterCrossings.isCrossing(waterAtWt, waterBelow);

		// ---- fix plan v4 D6-b: the writer never decides a height on its own.
		// A cell that cannot carry its solved height (outside the dimension bounds)
		// or whose profile write threw is offered the single-cell surface nudge, and
		// refused outright when that would step. The nudge's surface is the ground
		// found by scanning down, not the heightmap top.
		RoadWritePolicy.Column column = RoadWritePolicy.profileColumn(cell.solvedP, minY, maxY);
		if (!column.written()) {
			int nudgeTopY = ctx.groundSurfaceTopY(x, z, heightTopY);
			column = RoadWritePolicy.surfaceNudgeColumn(cell.solvedP, nudgeTopY, minY, maxY,
					neighbourSurfaces(net, cell), nearBuildingEntrance(shiftedComponents, x, z));
			if (column.written()) {
				NUDGED_WRITES.incrementAndGet();
			} else {
				// the old behaviour wrote the clamped height; keeping the counter so a
				// regression to "clamp and write" is still visible in /vps stats
				CLAMPED_WRITES.incrementAndGet();
				REFUSED_WRITES.incrementAndGet();
				warnRefused(cell, heightTopY);
				return;
			}
		}
		// fix plan v4 R2-3: the model-vs-real deviation. The solver works on the
		// noise model (see AGENTS.md: it runs during STRUCTURE_STARTS, where the
		// village has no terrain yet), so the written surface can differ from the
		// real one by a lot. That difference is MEASURED here - the writer is the
		// only place both heights exist - and warned about, never corrected.
		// The crossing surface is the water surface (scaffold) when the profile is
		// at or below it, and the solved surface (bridge) when it is above. A
		// profile below the water is raised to the water surface, so a crossing is
		// never below water (user decision 2026-10-15).
		int waterWalkSurface2 = 2 * heightTopY;
		int profileWalkSurface2 = column.walkSurface2();
		boolean aboveWater = crossing && profileWalkSurface2 > waterWalkSurface2;
		boolean scaffold = crossing && !aboveWater;
		int writtenWalkSurface2 = crossing
				? RoadWritePolicy.crossingWrittenWalkSurface2(profileWalkSurface2, waterWalkSurface2)
				: profileWalkSurface2;
		if (crossing) {
			if (scaffold) {
				if (waterAtWt) {
					VpsPipeline.SCAFFOLD_WATER_SELF.incrementAndGet();
				} else if (waterBelow) {
					VpsPipeline.SCAFFOLD_WATER_BELOW.incrementAndGet();
				} else {
					VpsPipeline.SCAFFOLD_DRY.incrementAndGet();
				}
			} else {
				WATER_BRIDGE_CELLS.incrementAndGet();
			}
		}
		recordDeviation(cell, heightTopY, writtenWalkSurface2, config, village);
		if (column.overrodeSolved()) {
			OVERRIDDEN_WRITES.incrementAndGet();
		}
		boolean slab = column.slab();
		int roadTopBlockY = RoadWritePolicy.roadBlockY(writtenWalkSurface2, scaffold ? false : slab);

		// Per-cell directional probes (user decision 2026-10-14). Both start at the
		// road block: a standable block there means the road is cut into terrain
		// (nothing to fill), an air block above means there is nothing to clear.
		int groundTopY = ctx.groundSurfaceTopY(x, z, roadTopBlockY);
		int ceilingTopY = ctx.ceilingSurfaceTopY(x, z, roadTopBlockY + 1);

		// ---- over-budget observation (fix plan v2 R3, decision N7): the writer
		// is a renderer, not a second solver. A cell whose solved walk surface
		// is beyond every affordable fill/cut is COUNTED and warned about, and
		// the solved height is written as-is: above the ground the fill cap
		// renders it as a bridge deck, below the ceiling the clear cap decides
		// tunnel vs open cut. The old snap onto the real surface is gone - it
		// manufactured a |dP| >= 2 step against the neighbouring solved cells
		// (exactly the drop class N1 forbids) to hide a probe error that this
		// counter now exposes instead.
		if (config.roadSmoothing.enabled && !aboveWater && !scaffold
				&& RoadWritePolicy.overBudget(cell.solvedP, groundTopY, ceilingTopY,
						config.roadSmoothing.maxFillLayers, config.roadSmoothing.maxClearLayers)) {
			FALLBACK_REANCHORS.incrementAndGet();
			if (!warnedFallbackReanchors) {
				warnedFallbackReanchors = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} solved walk surface {} is beyond the fill/cut budget (ground {} / ceiling {}); kept the solved height - observation, not corrected; a non-zero count means the probe or solver was off", x, z, cell.solvedP / 2.0, groundTopY, ceilingTopY);
			}
		}

		int ceilingBottomY = RoadWritePolicy.NO_CEILING;
		if (config.roadSmoothing.enabled) {
			// The walk surface and the support fill are separate materials now:
			// the non-desert surface is mud bricks, while the fill below it stays
			// the vanilla dirt path (user decision 2026-10-17).
			Block roadBlock = materials.roadBlock;

			if (aboveWater) {
				// A crossing above the water is always a bridge deck: no scaffold
				// and no support fill from the waterbed, however close the bottom is
				// (user decision 2026-10-15). The deck stays at the solved height.
				if (slab) {
					setWaterloggableBlockUnlessProtected(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
							.defaultBlockState()
							.setValue(SlabBlock.TYPE, SlabType.TOP), FEATURE_ROAD_BLOCKS_SKIPPED);
				}
				BlockState roadState = slab
						? materials.slabBlock.defaultBlockState()
						: roadBlock.defaultBlockState();
				setWaterloggableBlockUnlessProtected(ctx, x, roadTopBlockY, z, roadState,
						FEATURE_ROAD_BLOCKS_SKIPPED);
			} else if (scaffold) {
				// Pure scaffold deck (user decision 2026-10-15): the scaffold IS
				// the crossing's walk surface (user decision 2026-10-14) and the
				// water stays under it - the old support fill from the waterbed
				// (the underwater dirt causeway) is gone. The placed distance is
				// the configured two-value scheme again (user decision
				// 2026-10-16: no support reads ONE distance everywhere, 1 by
				// default); unsupported spans still decay via the vanilla tick,
				// which is the knowingly accepted Q04 behaviour.
				if (placeScaffolding(ctx, x, z, roadTopBlockY, isWater(ctx, x, roadTopBlockY, z), config)) {
					VpsPipeline.SCAFFOLDS_PLACED.incrementAndGet();
				}
			} else {
				// Support below the walk surface, down to the ground the downward
				// probe found (never above it); when the fill exceeds maxFillLayers
				// the column becomes a bridge deck instead (whole-block road: the
				// single road block; slab road: the road slab rides on an
				// upside-down top slab of the same material).
				int fillLayers = roadTopBlockY - groundTopY;
				boolean bridge = RoadWritePolicy.bridgeDeck(fillLayers, config.roadSmoothing.maxFillLayers);
				if (bridge) {
					OVER_BUDGET_CELLS.incrementAndGet();
				} else {
					fillSupport(ctx, x, z, groundTopY, roadTopBlockY, materials, config);
				}

				if (bridge && slab) {
					setWaterloggableBlockUnlessProtected(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
							.defaultBlockState()
							.setValue(SlabBlock.TYPE, SlabType.TOP), FEATURE_ROAD_BLOCKS_SKIPPED);
				}
				BlockState roadState = slab
						? materials.slabBlock.defaultBlockState()
						: roadBlock.defaultBlockState();
				setWaterloggableBlockUnlessProtected(ctx, x, roadTopBlockY, z, roadState,
						FEATURE_ROAD_BLOCKS_SKIPPED);
			}
			// User decision 2026-10-17: the clear end is re-read from the real
			// column at write time ("highest block with a collision or a fluid"),
			// not the possibly-stale height spectrum. The cap still applies, and
			// building blocks are never cleared.
			int highestBlockY = ctx.highestCollisionOrFluidY(x, z, roadTopBlockY + 1);
			int clearSurfaceTopY = highestBlockY + 1;
			int clearTop = RoadWritePolicy.clearTopY(roadTopBlockY, clearSurfaceTopY,
					config.roadSmoothing.maxClearLayers);
			// Clear upward to min(clear cap, the fresh column top): never stop at the
			// first air, so a cave inside the cap is cleared out too.
			clearAbove(ctx, x, z, roadTopBlockY, clearTop, buildingBoxes);
			if (RoadWritePolicy.tunnelRoof(roadTopBlockY, clearSurfaceTopY, clearTop, config.roadSmoothing.maxClearLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
			}
			// The road's ceiling is the lowest collision/fluid block above the
			// cleared space: the cap-formed terrain or a natural cave roof. It
			// bounds BOTH the single ceiling layer and the kerb wall (user
			// decision 2026-10-17: no kerb above the ceiling, no ceiling beyond
			// the road column).
			int ceilingStart = clearTop + 1;
			ceilingBottomY = ceilingStart > ctx.getMaxY() || aboveWater
					? RoadWritePolicy.NO_CEILING
					: ctx.firstCollisionOrFluidY(x, z, ceilingStart);
			// User decision 2026-10-15 (kerb rework), extended 2026-10-17: only
			// the bottom block of the ceiling is lined with the kerb material.
			// When the clear stopped at its cap, that block is the cap-formed
			// ceiling (glowstone on a straight 3-wide middle cell); a natural roof
			// beyond the cap keeps the kerb material and gets a hanging lantern on
			// the middle cell instead. Plaza cells never reach here (their early
			// return above protects the fountain).
			if (config.kerb.enabled && !aboveWater) {
				lineCeiling(ctx, x, z, clearTop, highestBlockY, config.roadSmoothing.maxClearLayers,
						ceilingBottomY, threeWideMiddles.get(cell.index), buildingBoxes, materials);
			}
		}
		VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();

		// ---- kerbs and railings on side edges (5.5)
		applyKerbsAndRailings(net, cell, ctx, config, materials, roadTopBlockY, writtenWalkSurface2,
				sideFacts, countedEdgeColumns, buildingBoxes, doorFronts, ceilingBottomY);
	}

	/**
	 * Lines the single lowest block of the ceiling above the cleared column with
	 * the kerb material and, on the middle cell of a straight 3-wide road, adds
	 * the ceiling light (user decision 2026-10-17: one layer, road column only).
	 *
	 * <p>Two cases:
	 * <ul>
	 *   <li>the clear stopped at its cap and the next block is terrain (a
	 *       cap-formed ceiling): that bottom block becomes glowstone on a middle
	 *       cell - and nowhere else the slab on top, so the roof thickness is
	 *       untouched;</li>
	 *   <li>the next block is air and a natural cave roof lies above: only the
	 *       roof's lowest block is lined with the kerb material and a hanging
	 *       lantern is placed in the clear cap cell, connected to the roof by an
	 *       iron chain - no glowstone.</li>
	 * </ul>
	 * A building block is never replaced.
	 */
	private static void lineCeiling(VpsGenContext ctx, int x, int z, int clearTop, int highestBlockY,
			int maxClearLayers, int ceilingBottomY, boolean middle, int[][] buildingBoxes,
			RoadMaterialTable.StyleMaterials materials) {
		if (ceilingBottomY == RoadWritePolicy.NO_CEILING || ceilingBottomY > ctx.getMaxY()) {
			return; // open sky
		}
		// User decision 2026-10-17: a non-terrain decoration as the lowest ceiling
		// block is left untouched - no glowstone, no kerb lining, no lantern/chain.
		if (ctx.isProtectedDecoration(x, ceilingBottomY, z)) {
			FEATURE_CEILING_BLOCKS_SKIPPED.incrementAndGet();
			return;
		}
		boolean capFormed = RoadWritePolicy.capFormedCeiling(clearTop, highestBlockY, maxClearLayers, ceilingBottomY);
		boolean protectedBlock = RoadWritePolicy.insideBuildingAt(buildingBoxes, x, ceilingBottomY, z);
		if (capFormed && middle) {
			if (!protectedBlock) {
				setBlock(ctx, x, ceilingBottomY, z, Blocks.GLOWSTONE.defaultBlockState());
				CEILING_GLOWSTONE_PLACED.incrementAndGet();
			}
			return; // the glowstone IS the one ceiling layer
		}
		if (!protectedBlock) {
			setBlock(ctx, x, ceilingBottomY, z, materials.kerbBlock.defaultBlockState());
			ROOF_LINED_BLOCKS.incrementAndGet();
		}
		if (!capFormed && middle) {
			// Natural cave roof above the clear cap: hang the lantern from it.
			placeCeilingLantern(ctx, x, z, clearTop + 1, ceilingBottomY, buildingBoxes);
		}
	}

	/** Hanging lantern at {@code lanternY}, with an iron chain up to the natural roof. */
	private static void placeCeilingLantern(VpsGenContext ctx, int x, int z, int lanternY, int roofY,
			int[][] buildingBoxes) {
		if (lanternY >= ctx.getMinY() && lanternY <= ctx.getMaxY()
				&& !RoadWritePolicy.insideBuildingAt(buildingBoxes, x, lanternY, z)) {
			BlockState lantern = Blocks.LANTERN.defaultBlockState();
			if (lantern.hasProperty(LanternBlock.HANGING)) {
				lantern = lantern.setValue(LanternBlock.HANGING, true);
			}
			if (!setBlockUnlessProtected(ctx, x, lanternY, z, lantern, FEATURE_CEILING_BLOCKS_SKIPPED)) {
				return; // no lantern: do not hang a chain into nothing
			}
			CEILING_LANTERNS_PLACED.incrementAndGet();
		}
		for (int y = lanternY + 1; y < roofY; y++) {
			if (y < ctx.getMinY() || y > ctx.getMaxY()
					|| RoadWritePolicy.insideBuildingAt(buildingBoxes, x, y, z)) {
				continue;
			}
			BlockState chain = Blocks.CHAIN.defaultBlockState();
			if (chain.hasProperty(ChainBlock.AXIS)) {
				chain = chain.setValue(ChainBlock.AXIS, Direction.Axis.Y);
			}
			if (setBlockUnlessProtected(ctx, x, y, z, chain, FEATURE_CEILING_BLOCKS_SKIPPED)) {
				CEILING_CHAINS_PLACED.incrementAndGet();
			}
		}
	}

	/** Support fill from the ground block up to (not including) the road block. */
	private static void fillSupport(VpsGenContext ctx, int x, int z, int groundTopY, int roadTopBlockY,
			RoadMaterialTable.StyleMaterials materials, VillagePathSmoothedConfig config) {
		// The fill is deliberately NOT the new mud-brick surface: the dirt path
		// packed under the road stays unchanged (user decision 2026-10-17). A
		// configured fillMaterial still overrides it.
		Block fillBlock = materials.fillBlock;
		if (!"STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)) {
			Block configured = VpsCompat.blockById(config.roadSmoothing.fillMaterial);
			if (configured != null) {
				fillBlock = configured;
			}
		}
		for (int y = groundTopY - 1; y < roadTopBlockY; y++) {
			setBlockUnlessProtected(ctx, x, y, z, fillBlock.defaultBlockState(),
					FEATURE_FILL_BLOCKS_SKIPPED);
		}
	}

	/**
	 * Clears the terrain left above the road block, up to {@code clearTop}
	 * (already clamped by the cap and the freshly re-read column top). A building
	 * block inside a protected box is never cleared, while road decorations
	 * (kerbs, leaves, scaffolding) outside those boxes are not protected.
	 */
	private static void clearAbove(VpsGenContext ctx, int x, int z, int roadTopBlockY, int clearTop,
			int[][] buildingBoxes) {
		for (int y = roadTopBlockY + 1; y <= clearTop; y++) {
			if (RoadWritePolicy.insideBuildingAt(buildingBoxes, x, y, z)) {
				continue;
			}
			setBlockUnlessProtected(ctx, x, y, z, Blocks.AIR.defaultBlockState(),
					FEATURE_CLEAR_BLOCKS_SKIPPED);
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

	/**
	 * Fix plan v4 R2-3: the model-vs-real deviation of one written cell.
	 *
	 * <p>The solver's {@code baseP} is the <b>noise model</b>, not the world: the
	 * solve runs during STRUCTURE_STARTS, before any chunk of the village has
	 * terrain, so carvers, surface rules and the terrain adaptation around buildings
	 * are all invisible to it (see AGENTS.md). The writer is the only place where
	 * both the model-derived height and the real one exist, so this is where the
	 * difference is measured.
	 *
	 * <p>It is measured and warned about, never corrected. Correcting it would mean
	 * either re-solving (forbidden) or snapping a cell onto the terrain, which is
	 * exactly the |dP| &gt;= 2 step N1 forbids. A WARN is not a degradation either:
	 * the road is still written from the solution, which is the promise no-cut and
	 * the fill/cut caps are about.
	 */
	private static void recordDeviation(RoadCell cell, int realTopY, int writtenWalkSurface2,
			VillagePathSmoothedConfig config, String village) {
		int dev = Math.abs(writtenWalkSurface2 - 2 * realTopY);
		DEVIATION_CELLS.incrementAndGet();
		DEVIATION_SUM.addAndGet(dev);
		int worst = (int) DEVIATION_WORST.accumulateAndGet(dev, Math::max);
		if (writtenWalkSurface2 < 2 * realTopY - 2) {
			CUT_BELOW_TERRAIN_CELLS.incrementAndGet();
		} else if (writtenWalkSurface2 > 2 * realTopY + 2) {
			FILL_ABOVE_TERRAIN_CELLS.incrementAndGet();
		}
		int threshold = 2 * Math.max(0, config.roadSmoothing.surfaceDeviationWarnBlocks);
		if (threshold <= 0 || dev <= threshold) {
			return;
		}
		DEVIATION_OVER_THRESHOLD_CELLS.incrementAndGet();
		if (DEVIATION_WARNED.compareAndSet(false, true)) {
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {}: road cell {} is {} half units ({} blocks) from the real terrain, past surfaceDeviationWarnBlocks={}. The solver works on the noise model - it runs before the village has any terrain, so carvers and terrain adaptation are invisible to it - so a non-zero deviation is expected. It is reported here and NOT corrected: the road still follows the solution.",
					village, cell.x + " " + cell.z, dev, dev / 2.0, config.roadSmoothing.surfaceDeviationWarnBlocks);
		} else if (dev > worst) {
			// keep the worst known number visible without warning per cell
			VillagePathSmoothed.LOGGER.debug("[VPS] village at {}: worst model-vs-terrain deviation so far {} half units at cell {} {}",
					village, worst, cell.x, cell.z);
		}
	}

	/**
	 * Kerbs and railings on the road's side edges (5.5).
	 *
	 * <p>User decisions 2026-10-14 (directional probes) and 2026-10-15 (kerb
	 * rework): the side column is probed <b>from the road block's own Y</b>. Two
	 * probes decide the two details independently:
	 *
	 * <ul>
	 *   <li><b>Kerb (the wall)</b>: probe the first and second cell outward from
	 *       the road block's level through air gaps; the highest collision/fluid
	 *       block in either column is the probe top. The old drop threshold is
	 *       kept (the side must rise more than {@code kerb.minDropHeight}), but
	 *       every layer is wrapped when <em>either</em> of the two columns has a
	 *       collision or a fluid. The first column is filled even where it was
	 *       air, and the material is no longer limited to dirt. Building blocks
	 *       are never replaced.</li>
	 *   <li><b>Railing</b>: scan <b>down</b> to the first standable ground and
	 *       apply a railing when the road is more than {@code railing.minDropHeight}
	 *       above it. Conditions unchanged: the door skip, the water rule and the
	 *       threshold are exactly what they were.</li>
	 * </ul>
	 *
	 * <p>The old code read {@code WORLD_SURFACE_WG} for the side column, so an
	 * overhang or a canopy above the edge faked a huge drop and a shelf under it
	 * faked a railing in the wrong place; the directional probes keep both
	 * decisions honest.
	 *
	 * @param writtenWalkSurface2 the walk surface actually written (a crossing's
	 *        scaffold is a full block, so it is half a unit above a slab)
	 */
	private static void applyKerbsAndRailings(RoadNetwork net, RoadCell cell, VpsGenContext ctx, VillagePathSmoothedConfig config,
			RoadMaterialTable.StyleMaterials materials, int roadTopBlockY, int writtenWalkSurface2,
			Map<SideKey, SideFacts> sideFacts, Set<Long> countedEdgeColumns, int[][] buildingBoxes,
			int[][] doorFronts, int ceilingBottomY) {
		int sRoad2 = writtenWalkSurface2;
		for (int dir = 0; dir < 4; dir++) {
			int outX = cell.x + SIDE_DX[dir];
			int outZ = cell.z + SIDE_DZ[dir];
			if (net.cellAt(outX, outZ) >= 0) {
				continue; // road continues this way: no side edge
			}
			// 2026-10-17: never dress a side toward the town-centre piece's own
			// y=0 footprint. The well/fountain water and jigsaw cells are not road
			// cells, so they look like an edge; wrapping a kerb into them plugged
			// the central well shut. The plaza's OUTER boundary is untouched (its
			// side cell is outside the piece footprint).
			if (net.plazaFootprint.contains(RoadCell.packedXZ(outX, outZ))) {
				continue;
			}
			// User decision 2026-10-17 ("探2包1"): probe the first and the second
			// cell outward, but still wrap only the first cell. The second cell can
			// be the one that carries the terrain (a one-block gap between road and
			// hillside), in which case the first cell is filled up to the probed top.
			int farX = outX + SIDE_DX[dir];
			int farZ = outZ + SIDE_DZ[dir];
			SideFacts facts = sideFacts.computeIfAbsent(new SideKey(outX, outZ, roadTopBlockY),
					k -> probeSideFacts(ctx, k.x(), k.z(), k.roadTopBlockY()));
			if (facts == null) {
				continue; // unreadable column: never fatal, skip the details here
			}
			SideFacts farFacts = sideFacts.computeIfAbsent(new SideKey(farX, farZ, roadTopBlockY),
					k -> probeSideFacts(ctx, k.x(), k.z(), k.roadTopBlockY()));
			int probeTopY = facts.topmostY();
			if (farFacts != null) {
				probeTopY = Math.max(probeTopY, farFacts.topmostY());
			}

			// the side surface the kerb threshold measures against: the two-column
			// probe top when it has any block at/above the road level, the first
			// column's ground below when it is empty up there
			boolean rises = probeTopY != Integer.MIN_VALUE;
			int sSide2 = rises ? 2 * (probeTopY + 1) : 2 * facts.groundTopY();
			// the surface the railing guards against: always the WALKABLE ground
			// below, so an open edge under a higher shelf still counts as a drop
			int sSideGround2 = 2 * facts.groundTopY();

			// fix plan v4 D4-a: measure the mask-edge drop once per side column
			// (every road cell still measures it; the metric just counts a column
			// once - the ownership rule that used to guarantee that is gone)
			if (countedEdgeColumns.add(RoadCell.packedXZ(outX, outZ))) {
				int lateralDrop = Math.abs(sSide2 - sRoad2);
				if (lateralDrop > 2) {
					EDGE_DROP_VIOLATIONS.incrementAndGet();
					EDGE_WORST_DROP.accumulateAndGet(lateralDrop, Math::max);
				}
			}

			if (config.kerb.enabled && RoadWritePolicy.kerbApplies(sSide2, sRoad2, config.kerb.minDropHeight)) {
				int wallTop = placeKerbWall(ctx, outX, outZ, farX, farZ, roadTopBlockY, probeTopY, ceilingBottomY,
						buildingBoxes, materials);
				if (wallTop != Integer.MIN_VALUE) {
					// 2026-10-17: close the wall's along-road end where the next
					// cell already sees open sky, so the top does not stop raw.
					capKerbRunEnds(net, ctx, cell.x, cell.z, outX, outZ, roadTopBlockY, wallTop,
							ceilingBottomY, buildingBoxes, materials, sideFacts);
				}
			}

			if (!config.railing.enabled) {
				continue;
			}
			// User decision 2026-10-15: the single block in front of a movable
			// building's door is never fenced, so a doorway cannot be closed off. It
			// overrides both the drop rule and the water rule below.
			if (config.railing.skipAtBuildingDoors && doorFrontBlocksRailing(doorFronts, outX, outZ)) {
				RAILINGS_SKIPPED_AT_DOOR.incrementAndGet();
				continue;
			}
			// User decision 2026-10-15: a water side column overrides the drop
			// threshold - no railing while the road is not above the water surface,
			// a railing as soon as it is (even half a block). This is what stops a
			// railing from being planted into the water beside a water-level road.
			Integer waterTopY = sideWaterTopY(ctx, outX, outZ, roadTopBlockY);
			if (waterTopY != null) {
				int waterWalkSurface2 = 2 * (waterTopY + 1);
				if (sRoad2 > waterWalkSurface2) {
					placeRailing(ctx, outX, outZ, roadTopBlockY + 1, materials);
				} else {
					RAILINGS_SKIPPED_AT_WATER.incrementAndGet();
				}
				continue;
			}
			if (RoadWritePolicy.railingApplies(sSideGround2, sRoad2, config.railing.minDropHeight)) {
				placeRailing(ctx, outX, outZ, roadTopBlockY + 1, materials);
			}
		}
	}

	/** Highest water block at the side column within two blocks below the road block, or null when dry there. */
	private static Integer sideWaterTopY(VpsGenContext ctx, int x, int z, int roadTopBlockY) {
		for (int dy = 0; dy <= 1; dy++) {
			if (isWater(ctx, x, roadTopBlockY - dy, z)) {
				return roadTopBlockY - dy;
			}
		}
		return null;
	}

	/** True when the side column is the door front of a movable building (within one block). */
	private static boolean doorFrontBlocksRailing(int[][] doorFronts, int x, int z) {
		if (doorFronts == null) {
			return false;
		}
		for (int[] door : doorFronts) {
			if (Math.abs(x - door[0]) <= 1 && Math.abs(z - door[1]) <= 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Wraps the first cell outward from one above the road block up to the road's
	 * ceiling (user decision 2026-10-17). At every layer, if the first or the
	 * second cell has a collision or a fluid, the first cell gets the kerb
	 * material - even when it was air before, which turns a one-block setback
	 * into a retaining wall. The block material is no longer restricted to the
	 * dirt tag. A building block is never replaced.
	 *
	 * <p>When the road has a ceiling, the wall stops one block below it
	 * ({@link RoadWritePolicy#kerbTopY}): the ceiling layer belongs to the road
	 * column only, and the kerb never climbs the hillside above the tunnel. Open
	 * sky keeps the old "wrap to the probed side top" behaviour.
	 *
	 * <p>A leaf layer is placed above the top of every contiguous wrapped run
	 * (the natural terrain top and every mid-column air-gap top). A run that was
	 * artificially cut off at the ceiling gets no leaf layer, so nothing is
	 * planted at the ceiling level outside the road column.
	 */
	private static int placeKerbWall(VpsGenContext ctx, int x, int z, int farX, int farZ, int roadTopBlockY,
			int topmostY, int ceilingBottomY, int[][] buildingBoxes, RoadMaterialTable.StyleMaterials materials) {
		boolean placed = false;
		boolean runWrapped = false;
		int runTopY = Integer.MIN_VALUE;
		int topWrittenY = Integer.MIN_VALUE;
		int limit = Math.min(ctx.getMaxY(), RoadWritePolicy.kerbTopY(topmostY, ceilingBottomY));
		for (int y = roadTopBlockY + 1; y <= limit; y++) {
			// User decision 2026-10-17: a non-terrain decoration breaks the kerb
			// run. Skip the decoration block itself (never wrap through it) and
			// close the current run before the skip, so the wall neither replaces
			// the tree nor floats over it.
			if (ctx.isProtectedDecoration(x, y, z)) {
				if (runWrapped) {
					placeKerbRailing(ctx, x, z, runTopY + 1, buildingBoxes, materials);
				}
				runWrapped = false;
				runTopY = Integer.MIN_VALUE;
				FEATURE_KERB_BLOCKS_SKIPPED.incrementAndGet();
				continue;
			}
			boolean nearTerrain = ctx.hasCollisionOrFluid(x, y, z);
			boolean farTerrain = !ctx.isProtectedDecoration(farX, y, farZ)
					&& ctx.hasCollisionOrFluid(farX, y, farZ);
			if (nearTerrain || farTerrain) {
				runTopY = y;
				if (!RoadWritePolicy.insideBuildingAt(buildingBoxes, x, y, z)) {
					setBlock(ctx, x, y, z, materials.kerbBlock.defaultBlockState());
					placed = true;
					runWrapped = true;
					topWrittenY = y;
				}
				continue;
			}
			if (runWrapped) {
				placeKerbRailing(ctx, x, z, runTopY + 1, buildingBoxes, materials);
			}
			runWrapped = false;
			runTopY = Integer.MIN_VALUE;
		}
		if (runWrapped && (ceilingBottomY == RoadWritePolicy.NO_CEILING || runTopY + 1 < ceilingBottomY)) {
			placeKerbRailing(ctx, x, z, runTopY + 1, buildingBoxes, materials);
		}
		if (placed) {
			VpsPipeline.KERBS_PLACED.incrementAndGet();
		}
		return topWrittenY;
	}

	/**
	 * Closes a wrapped kerb run's along-road end (user request 2026-10-17).
	 *
	 * <p>The wall runs along the road on side {@code S}; {@code (cellX,cellZ)}
	 * plus {@code S} is the wrapped column {@code (outX,outZ)}. Along the road
	 * means perpendicular to {@code S} (direction {@code T}) - but only where a
	 * road cell actually continues at {@code cell + T}: an end wall (the road's
	 * forward side) must never grow a cross-road column.
	 *
	 * <p>At the end the next road cell's side cell {@code out + T} already sees
	 * open sky, so that cell gets no wall. The cap is built <b>above the existing
	 * wall top</b>, not from the road: {@code wallTop + 1} is always placed, and
	 * the layers above it use the same probe-2-wrap-1 rule as the kerb - probe
	 * the cap cell and its outward cell, wrap the cap only where either has a
	 * collision or a fluid, up to the probed top. The top of every wrapped run
	 * gets the leaf kerb railing. Nothing is written into a road cell or the
	 * town-centre footprint, and the ceiling still caps the height.
	 */
	private static void capKerbRunEnds(RoadNetwork net, VpsGenContext ctx, int cellX, int cellZ,
			int outX, int outZ, int roadTopBlockY, int wallTop, int ceilingBottomY,
			int[][] buildingBoxes, RoadMaterialTable.StyleMaterials materials,
			Map<SideKey, SideFacts> sideFacts) {
		try {
			capKerbRunEnds0(net, ctx, cellX, cellZ, outX, outZ, roadTopBlockY, wallTop, ceilingBottomY,
					buildingBoxes, materials, sideFacts);
		} catch (Throwable t) {
			// the cap is cosmetic: an unreadable column must not fail the cell write
		}
	}

	private static void capKerbRunEnds0(RoadNetwork net, VpsGenContext ctx, int cellX, int cellZ,
			int outX, int outZ, int roadTopBlockY, int wallTop, int ceilingBottomY,
			int[][] buildingBoxes, RoadMaterialTable.StyleMaterials materials,
			Map<SideKey, SideFacts> sideFacts) {
		int sideX = outX - cellX;
		int sideZ = outZ - cellZ;
		// along the road = perpendicular to the kerb's side direction
		int alongX = sideZ;
		int alongZ = sideX;
		int capLimitY = ceilingBottomY == RoadWritePolicy.NO_CEILING
				? ctx.getMaxY()
				: Math.min(ctx.getMaxY(), ceilingBottomY - 1);
		int firstY = wallTop + 1;
		if (firstY > capLimitY) {
			return; // the wall already reaches the ceiling
		}
		for (int sign = -1; sign <= 1; sign += 2) {
			int nextRoadX = cellX + sign * alongX;
			int nextRoadZ = cellZ + sign * alongZ;
			if (net.cellAt(nextRoadX, nextRoadZ) < 0) {
				continue; // no road continuing along the wall: not an along-road end
			}
			int capX = outX + sign * alongX;
			int capZ = outZ + sign * alongZ;
			if (net.cellAt(capX, capZ) >= 0) {
				continue; // a road cell there: never write a kerb into it
			}
			if (net.plazaFootprint.contains(RoadCell.packedXZ(capX, capZ))) {
				continue; // town-centre interior
			}
			SideFacts capFacts = sideFacts.computeIfAbsent(new SideKey(capX, capZ, roadTopBlockY),
					k -> probeSideFacts(ctx, k.x(), k.z(), k.roadTopBlockY()));
			if (capFacts == null || capFacts.topmostY() > roadTopBlockY) {
				continue; // the wall continues there, or the column is unreadable
			}
			int farCapX = capX + sideX;
			int farCapZ = capZ + sideZ;
			SideFacts farCapFacts = sideFacts.computeIfAbsent(new SideKey(farCapX, farCapZ, roadTopBlockY),
					k -> probeSideFacts(ctx, k.x(), k.z(), k.roadTopBlockY()));
			int probeTop = farCapFacts == null
					? Integer.MIN_VALUE
					: Math.max(capFacts.topmostY(), farCapFacts.topmostY());
			int capTopY = Math.min(capLimitY, Math.max(firstY, probeTop));
			// Probe-2-wrap-1 above the wall top, with the wall-top+1 layer always
			// placed so the end closes even when the sky-adjacent column is empty.
			boolean runWrapped = false;
			int runTopY = Integer.MIN_VALUE;
			boolean placed = false;
			for (int y = firstY; y <= capTopY; y++) {
				if (ctx.isProtectedDecoration(capX, y, capZ)) {
					if (runWrapped) {
						placeKerbRailing(ctx, capX, capZ, runTopY + 1, buildingBoxes, materials);
					}
					runWrapped = false;
					runTopY = Integer.MIN_VALUE;
					FEATURE_KERB_BLOCKS_SKIPPED.incrementAndGet();
					continue;
				}
				boolean nearTerrain = y == firstY || ctx.hasCollisionOrFluid(capX, y, capZ);
				boolean farTerrain = farCapFacts != null
						&& !ctx.isProtectedDecoration(farCapX, y, farCapZ)
						&& ctx.hasCollisionOrFluid(farCapX, y, farCapZ);
				if (nearTerrain || farTerrain) {
					runTopY = y;
					if (!RoadWritePolicy.insideBuildingAt(buildingBoxes, capX, y, capZ)) {
						setBlock(ctx, capX, y, capZ, materials.kerbBlock.defaultBlockState());
						placed = true;
						runWrapped = true;
					}
					continue;
				}
				if (runWrapped) {
					placeKerbRailing(ctx, capX, capZ, runTopY + 1, buildingBoxes, materials);
				}
				runWrapped = false;
				runTopY = Integer.MIN_VALUE;
			}
			if (runWrapped) {
				placeKerbRailing(ctx, capX, capZ, runTopY + 1, buildingBoxes, materials);
			}
			if (placed) {
				VpsPipeline.KERBS_PLACED.incrementAndGet();
			}
		}
	}

	/** One leaf layer on top of a wrapped kerb run; never replaces a building block. */
	private static void placeKerbRailing(VpsGenContext ctx, int x, int z, int y, int[][] buildingBoxes,
			RoadMaterialTable.StyleMaterials materials) {
		if (y < ctx.getMinY() || y > ctx.getMaxY()
				|| RoadWritePolicy.insideBuildingAt(buildingBoxes, x, y, z)) {
			return;
		}
		BlockState leaves = materials.leavesBlock.defaultBlockState();
		if (leaves.hasProperty(LeavesBlock.PERSISTENT)) {
			leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
		}
		if (setWaterloggableBlockUnlessProtected(ctx, x, y, z, leaves, FEATURE_RAILING_BLOCKS_SKIPPED)) {
			KERB_RAILINGS_PLACED.incrementAndGet();
		}
	}

	private static void placeRailing(VpsGenContext ctx, int x, int z, int walkSurfaceY, RoadMaterialTable.StyleMaterials materials) {
		BlockState existing = ctx.blockState(x, walkSurfaceY, z);
		if (!RoadWritePolicy.mayOverwrite(FeatureProtection.isProtectedDecoration(existing))) {
			FEATURE_RAILING_BLOCKS_SKIPPED.incrementAndGet();
			return;
		}
		if (!existing.isAir() && !existing.canBeReplaced()) {
			return;
		}
		BlockState leaves = materials.leavesBlock.defaultBlockState();
		if (leaves.hasProperty(LeavesBlock.PERSISTENT)) {
			leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
		}
		setWaterloggableBlock(ctx, x, walkSurfaceY, z, leaves);
		VpsPipeline.RAILINGS_PLACED.incrementAndGet();
	}

	/**
	 * Places a block that carries the WATERLOGGED property (a road slab or a
	 * leaf railing), waterlogged when it lands in a water source block (user
	 * decision 2026-10-15 - rare, but a wet slab must not silently displace the
	 * water it was placed into). Full road blocks (dirt path, sandstone) are not
	 * waterloggable and never route through here.
	 */
	private static void setWaterloggableBlock(VpsGenContext ctx, int x, int y, int z, BlockState state) {
		if (state.hasProperty(BlockStateProperties.WATERLOGGED) && isWater(ctx, x, y, z)) {
			state = state.setValue(BlockStateProperties.WATERLOGGED, true);
			VpsPipeline.WATERLOGGED_PLACEMENTS.incrementAndGet();
		}
		setBlock(ctx, x, y, z, state);
	}

	/**
	 * Places the scaffolding block at {@code scaffoldY} - the solved road height
	 * since fix plan v4 D6-b, not the vanilla surface height.
	 *
	 * <p>waterlogged = water at the scaffold block itself; the caller passes that
	 * exact read, not the wider crossing predicate (review 3 F8-1).
	 *
	 * <p>The DISTANCE is the configured two-value scheme again (user decision
	 * 2026-10-16, restoring the 634cec6/f7ff0d2 behaviour): a sturdy block below
	 * anchors the column at {@code waterCrossing.distanceWhenSupported} (default
	 * 0), anything else reads {@code waterCrossing.distanceWhenUnsupported}
	 * (default 1) - no support, one distance, everywhere. The vanilla-mirror
	 * computation that propagated distance across scaffolding neighbours is
	 * reverted; a span with no support decays via the vanilla tick, which is the
	 * knowingly accepted Q04 behaviour, not a defect to fix silently.
	 */
	private static boolean placeScaffolding(VpsGenContext ctx, int x, int z, int scaffoldY, boolean waterlogged,
			VillagePathSmoothedConfig config) {
		if (!RoadWritePolicy.mayOverwrite(ctx.isProtectedDecoration(x, scaffoldY, z))) {
			FEATURE_ROAD_BLOCKS_SKIPPED.incrementAndGet();
			return false;
		}
		BlockPos belowPos = new BlockPos(x, scaffoldY - 1, z);
		BlockState below = ctx.blockState(x, scaffoldY - 1, z);
		// vanilla ScaffoldingBlock#getDistance counts a column as supported when
		// the block below has a sturdy UP face - NOT when it is a full collision
		// cube, so slabs or snow-covered grass count as support too; our writer
		// must use the identical criterion or the first tick will recompute a
		// different support verdict than we placed (the f7ff0d2 alignment, kept)
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
		return true;
	}

	/** True when the column has water at this exact Y (block or fluid state). */
	private static boolean isWater(VpsGenContext ctx, int x, int y, int z) {
		if (ctx.blockState(x, y, z).is(Blocks.WATER)) {
			return true;
		}
		return ctx.level.getFluidState(new BlockPos(x, y, z)).is(net.minecraft.tags.FluidTags.WATER);
	}

	/**
	 * Debug door signs (user request 2026-10-16, opt-in since 2026-10-17): one
	 * standing oak sign at every movable building's MAIN door front, documenting the
	 * door-to-road alignment on foot (door/basis fix plan 2026-10-17). Line 1:
	 * the door X/Z with {@code baseWalkY + dy} (the main piece's
	 * {@code minY + groundLevelDelta}); line 2: the selected road cell's X/Z
	 * and its solved walk surface (x.0 / x.5, locale-free integer formatting);
	 * line 3 the actual door floor; line 4 {@code NO FRONT} when no eligible
	 * outward cell existed. Numbers only, space separated. Signs are
	 * placed after every road cell of this chunk, and a sign whose column
	 * belongs to another chunk is placed when that chunk generates - the same
	 * incremental rule the road cells follow. Debug visibility beats fidelity
	 * (user decision 2026-10-16): the sign REPLACES whatever occupies the
	 * target cell and may float; only the world bounds are clamped so the
	 * write cannot be silently dropped. The water-seat base rules are
	 * deliberately untouched here.
	 */
	private static void writeDoorSigns(VpsGenContext ctx, BoundingBox chunkBB, int[][] doorSigns, RoadNetwork net) {
		if (doorSigns == null || doorSigns.length == 0) {
			return;
		}
		Map<Long, RoadCell> cellsByXZ = new HashMap<>();
		for (RoadCell cell : net.cells) {
			cellsByXZ.put(RoadCell.packedXZ(cell.x, cell.z), cell);
		}
		for (int[] sign : doorSigns) {
			int frontX = sign[0] + sign[3];
			int frontZ = sign[2] + sign[4];
			if (frontX < chunkBB.minX() || frontX > chunkBB.maxX() || frontZ < chunkBB.minZ() || frontZ > chunkBB.maxZ()) {
				continue; // the sign's own chunk places it
			}
			int signY;
			RoadCell front = cellsByXZ.get(RoadCell.packedXZ(frontX, frontZ));
			if (front != null) {
				// stand on the road surface just rendered (a slab surface leaves
				// the sign half a block up, like a vanilla sign on a slab)
				signY = RoadWritePolicy.roadBlockY(front.solvedP, (front.solvedP & 1) != 0) + 1;
			} else {
				signY = ctx.groundSurfaceTopY(frontX, frontZ, sign[1]);
				if (signY <= ctx.getMinY()) {
					signY = sign[1]; // no standable ground: float at the base walk level
				}
			}
			// debug visibility beats fidelity (user decision 2026-10-16): replace
			// whatever occupies the cell, may float; clamp into the world so the
			// write cannot be silently dropped
			signY = Math.max(ctx.getMinY(), Math.min(ctx.getMaxY(), signY));
			Direction facing = sign[3] > 0 ? Direction.EAST : sign[3] < 0 ? Direction.WEST
					: sign[4] > 0 ? Direction.SOUTH : Direction.NORTH;
			BlockState state = Blocks.OAK_SIGN.defaultBlockState()
					.setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(facing));
			setBlock(ctx, frontX, signY, frontZ, state);
			if (ctx.level.getBlockEntity(new BlockPos(frontX, signY, frontZ)) instanceof SignBlockEntity signBlock) {
				// R4-2 (user decision 2026-10-16): line 3 is the ACTUAL door floor
				// Y of the template's lower door half after the shift, so the
				// assumed reference on line 1 and the real floor can be compared
				String doorFloorY = sign.length > 8 ? Integer.toString(sign[8]) : "?";
				// D4 (user decision 2026-10-17): no eligible outward cell means
				// the building was not aligned to this sign's line 2; say so.
				String noFront = sign.length > 9 && sign[9] != 0 ? "NO FRONT" : "";
				setSignLines(signBlock,
						sign[0] + " " + sign[1] + " " + sign[2],
						sign[5] + " " + walkY(sign[6]) + " " + sign[7],
						doorFloorY,
						noFront);
				VpsPipeline.DOOR_SIGNS_PLACED.incrementAndGet();
			}
		}
	}

	/**
	 * Debug seam signs (user request 2026-10-16, opt-in since 2026-10-17): one
	 * standing sign above the anchor road cell of every mask seam, printing the two solved coordinates
	 * and - when the seam breaks the half-block guarantee - the known cause.
	 * Line 1/2 are "x y z" with y = solvedP / 2 (x.0 / x.5); line 3 is the
	 * cause, line 4 the magnitude, both empty on a legal seam. The sign stands
	 * one block ABOVE the anchor's written road block (unlike the door sign it
	 * does not replace the road block itself). Clamped into the world so the
	 * write cannot be silently dropped; placed when the anchor's own chunk
	 * generates, the same incremental rule the door signs follow.
	 */
	private static void writeSeamSigns(VpsGenContext ctx, BoundingBox chunkBB, int[][] seamSigns) {
		if (seamSigns == null || seamSigns.length == 0) {
			return;
		}
		for (int[] seam : seamSigns) {
			int x = seam[0];
			int z = seam[2];
			if (x < chunkBB.minX() || x > chunkBB.maxX() || z < chunkBB.minZ() || z > chunkBB.maxZ()) {
				continue; // the sign's own chunk places it
			}
			int roadTopBlockY = RoadWritePolicy.roadBlockY(seam[1], (seam[1] & 1) != 0);
			int signY = Math.max(ctx.getMinY(), Math.min(ctx.getMaxY(), roadTopBlockY + 1));
			BlockState state = Blocks.OAK_SIGN.defaultBlockState()
					.setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(Direction.NORTH));
			setBlock(ctx, x, signY, z, state);
			if (!(ctx.level.getBlockEntity(new BlockPos(x, signY, z)) instanceof SignBlockEntity signBlock)) {
				VpsPipeline.SEAM_SIGNS_NO_BE.incrementAndGet();
				continue;
			}
			String reason = SeamSigns.reasonName(seam[6]);
			int diff = Math.abs(seam[1] - seam[4]);
			String magnitude = reason.isEmpty() ? "" : "d " + walkY(diff);
			setSignLines(signBlock,
					seam[0] + " " + walkY(seam[1]) + " " + seam[2],
					seam[3] + " " + walkY(seam[4]) + " " + seam[5],
					reason,
					magnitude);
			VpsPipeline.SEAM_SIGNS_PLACED.incrementAndGet();
		}
	}

	/** Walk surface (half units) as "x.0" / "x.5", locale-free, no float formatting. */
	private static String walkY(int halfP) {
		return (halfP & 1) == 0 ? (halfP / 2) + ".0" : ((halfP - 1) / 2) + ".5";
	}

	/**
	 * Writes four sign lines with the version-correct API, swallowing the
	 * worldgen update failure.
	 *
	 * <p>A block entity created during worldgen has no {@code Level} yet (the
	 * level is only attached when the chunk is loaded into the server). The sign
	 * text API ends in {@code SignBlockEntity#markUpdated}, which calls
	 * {@code Level#sendBlockUpdated} and, when a real Level is attached, loads and
	 * waits on the very chunk being generated - a worldgen deadlock (observed:
	 * "a single server tick has taken 240000 ms", the village never finishes).
	 * With no Level the same call throws a NullPointerException instead. In both
	 * cases the text is assigned to the block entity BEFORE markUpdated runs, so
	 * swallowing the exception keeps the text: the block entity stays in the
	 * chunk and is serialised with it on save, exactly like a vanilla structure
	 * block entity. Do NOT attach a Level here.
	 */
	private static void setSignLines(SignBlockEntity signBlock, String l0, String l1, String l2, String l3) {
		try {
			//#if MC >= 260000
			//$$ SignText.Mutable text = signBlock.getText(SignTextSlot.FRONT).asMutable();
			//$$ text.setLine(0, Component.literal(l0));
			//$$ text.setLine(1, Component.literal(l1));
			//$$ text.setLine(2, Component.literal(l2));
			//$$ text.setLine(3, Component.literal(l3));
			//$$ signBlock.setText(text.asImmutable(), SignTextSlot.FRONT);
			//#elseif MC >= 12001
			//$$ SignText text = signBlock.getText(true);
			//$$ text = text.setMessage(0, Component.literal(l0));
			//$$ text = text.setMessage(1, Component.literal(l1));
			//$$ text = text.setMessage(2, Component.literal(l2));
			//$$ text = text.setMessage(3, Component.literal(l3));
			//$$ signBlock.setText(text, true);
			//#else
			signBlock.setMessage(0, Component.literal(l0));
			signBlock.setMessage(1, Component.literal(l1));
			signBlock.setMessage(2, Component.literal(l2));
			signBlock.setMessage(3, Component.literal(l3));
			//#endif
			signBlock.setChanged();
		} catch (Throwable worldgenUpdate) {
			// expected: the block entity has no Level during worldgen; the text
			// fields are already written and will be saved with the chunk
		}
	}

	/**
	 * User decision 2026-10-17: writes only when the current block is not a
	 * protected non-terrain decoration. Returns false (and counts) when the
	 * decoration wins; callers decide whether the rest of the operation should
	 * continue. Use the block-reading overload only on the writer path - debug
	 * signs deliberately bypass it.
	 */
	private static boolean setBlockUnlessProtected(VpsGenContext ctx, int x, int y, int z, BlockState state,
			AtomicLong skipped) {
		if (!RoadWritePolicy.mayOverwrite(ctx.isProtectedDecoration(x, y, z))) {
			skipped.incrementAndGet();
			return false;
		}
		setBlock(ctx, x, y, z, state);
		return true;
	}

	/** {@link #setBlockUnlessProtected} for waterloggable road slabs. */
	private static boolean setWaterloggableBlockUnlessProtected(VpsGenContext ctx, int x, int y, int z,
			BlockState state, AtomicLong skipped) {
		if (!RoadWritePolicy.mayOverwrite(ctx.isProtectedDecoration(x, y, z))) {
			skipped.incrementAndGet();
			return false;
		}
		setWaterloggableBlock(ctx, x, y, z, state);
		return true;
	}

	private static void setBlock(VpsGenContext ctx, int x, int y, int z, BlockState state) {
		if (y < ctx.getMinY() || y > ctx.getMaxY()) {
			return;
		}
		ctx.level.setBlock(new BlockPos(x, y, z), state, WRITE_FLAGS);
	}
}

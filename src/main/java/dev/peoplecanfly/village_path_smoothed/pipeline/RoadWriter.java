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
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
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
	 * User decision 2026-10-15 (kerb rework): blocks of the ceiling run directly
	 * above the walk surface that were lined with the kerb material - the whole
	 * contiguous run, no longer only the single block at the clear cap and no
	 * longer gated on a cap being configured at all.
	 */
	public static final AtomicLong ROOF_LINED_BLOCKS = new AtomicLong();
	/** Railings suppressed on a movable building's door front (user decision 2026-10-15). */
	public static final AtomicLong RAILINGS_SKIPPED_AT_DOOR = new AtomicLong();
	/** Railings suppressed beside water because the road is not above the water surface (user decision 2026-10-15). */
	public static final AtomicLong RAILINGS_SKIPPED_AT_WATER = new AtomicLong();
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
			int[][] shiftedComponents, int[][] buildingBoxes, int[][] doorFronts, String village) {
		RoadMaterialTable table = RoadMaterialTable.create(config);
		List<Block> kerbExtras = resolveKerbExtras(config);
		// User decision 2026-10-15: every road cell applies its own edge details
		// - the one-owner-per-side-column rule is gone. A shared column now gets
		// the low road's kerb AND the high road's railing, which is the point:
		// each road holds up its own edge. The side-column probes are memoised
		// per (column, road level) so a shared column is scanned once per height,
		// and the drop metric stays once per column.
		Map<SideKey, SideFacts> sideFacts = new HashMap<>();
		Set<Long> countedEdgeColumns = new HashSet<>();
		for (RoadCell cell : net.cells) {
			if (cell.x < chunkBB.minX() || cell.x > chunkBB.maxX() || cell.z < chunkBB.minZ() || cell.z > chunkBB.maxZ()) {
				continue; // written when this cell's own chunk generates
			}
			try {
				writeCell(net, cell, ctx, config, table, kerbExtras, sideFacts, countedEdgeColumns, shiftedComponents,
						buildingBoxes, doorFronts, village);
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
	 *   <li>{@code topmostY} - the highest block at or above the road block's
	 *       level, scanning UP through air gaps to the column's height-spectrum
	 *       top ({@link Integer#MIN_VALUE} when the column is empty up there);
	 *       this is the wall the kerb wraps and the threshold it is measured
	 *       against;</li>
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
			int topmost = Integer.MIN_VALUE;
			for (int y = roadTopBlockY; y <= ctx.getMaxY(); y++) {
				if (ctx.isSolidAt(x, y, z)) {
					topmost = y;
				}
			}
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
			RoadMaterialTable table, List<Block> kerbExtras, Map<SideKey, SideFacts> sideFacts,
			Set<Long> countedEdgeColumns, int[][] shiftedComponents,
			int[][] buildingBoxes, int[][] doorFronts, String village) {
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
			applyKerbsAndRailings(net, cell, ctx, config, plazaMaterials, kerbExtras,
					RoadWritePolicy.roadTopBlockY(cell.solvedP), cell.solvedP, sideFacts, countedEdgeColumns, doorFronts);
			return;
		}

		// ---- plan A (user decision 2026-10-15): a building always wins. A claimed
		// column whose road block would land inside a movable building's 3D box is
		// skipped entirely - no road block, no support fill, no upward clear - so
		// smoothing can never overwrite a building body. Counted so the yield is
		// visible in /vps stats.
		if (config.roadSmoothing.protectBuildings && insideBuilding(buildingBoxes, x, z, cell.solvedP)) {
			BUILDING_CELLS_SKIPPED.incrementAndGet();
			return;
		}

		RoadMaterialTable.StyleMaterials materials = table.forStyle(cell.style);
		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();
		// The raw heightmap read now only feeds the observations (the model-vs-real
		// deviation and the refused-write warning), the crossing predicate and the
		// upward clear end. Every FILL/CUT decision comes from the directional probes
		// below (user decision 2026-10-14).
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
		if (config.roadSmoothing.enabled && !aboveWater
				&& RoadWritePolicy.overBudget(cell.solvedP, groundTopY, ceilingTopY,
						config.roadSmoothing.maxFillLayers, config.roadSmoothing.maxClearLayers)) {
			FALLBACK_REANCHORS.incrementAndGet();
			if (!warnedFallbackReanchors) {
				warnedFallbackReanchors = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] road cell at {} {} solved walk surface {} is beyond the fill/cut budget (ground {} / ceiling {}); kept the solved height - observation, not corrected; a non-zero count means the probe or solver was off", x, z, cell.solvedP / 2.0, groundTopY, ceilingTopY);
			}
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

			if (aboveWater) {
				// A crossing above the water is always a bridge deck: no scaffold
				// and no support fill from the waterbed, however close the bottom is
				// (user decision 2026-10-15). The deck stays at the solved height.
				if (slab) {
					setBlock(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
							.defaultBlockState()
							.setValue(SlabBlock.TYPE, SlabType.TOP));
				}
				BlockState roadState = slab
						? materials.slabBlock.defaultBlockState()
						: roadBlock.defaultBlockState();
				setBlock(ctx, x, roadTopBlockY, z, roadState);
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

				if (scaffold) {
					// The scaffold IS the crossing's walk surface (user decision
					// 2026-10-14): replacing the road block is what makes a water
					// crossing read as a plank bridge instead of a road with an
					// invisible support block below it. Only AT the water surface
					// now; a crossing above it takes the bridge branch above.
					placeScaffolding(ctx, x, z, roadTopBlockY, isWater(ctx, x, roadTopBlockY, z), config);
					VpsPipeline.SCAFFOLDS_PLACED.incrementAndGet();
				} else {
					if (bridge && slab) {
						setBlock(ctx, x, roadTopBlockY - 1, z, materials.slabBlock
								.defaultBlockState()
								.setValue(SlabBlock.TYPE, SlabType.TOP));
					}
					BlockState roadState = slab
							? materials.slabBlock.defaultBlockState()
							: roadBlock.defaultBlockState();
					setBlock(ctx, x, roadTopBlockY, z, roadState);
				}
			}
			// Clear upward to min(clear cap, real surface top): never stop at the
			// first air, so a cave inside the cap is cleared out too (user decision
			// 2026-10-15). The real surface top replaces the contiguous-ceiling
			// probe as the clear end.
			clearAbove(ctx, x, z, roadTopBlockY, heightTopY, config);
			int clearTop = RoadWritePolicy.clearTopY(roadTopBlockY, heightTopY, config.roadSmoothing.maxClearLayers);
			if (RoadWritePolicy.tunnelRoof(roadTopBlockY, heightTopY, clearTop, config.roadSmoothing.maxClearLayers)) {
				OVER_BUDGET_CELLS.incrementAndGet();
			}
			// User decision 2026-10-15 (kerb rework): the ceiling run directly above
			// the walk surface is lined with the kerb material for its whole
			// contiguous length - "转包顶". Whatever the capped clear left above the
			// road (a tunnel roof, an overhang past the cap) gets the full lining;
			// with the default unlimited clear the run is empty and this is a no-op.
			// This replaces the old single-block lining at the clear cap and no
			// longer depends on a cap being configured. Plaza cells never reach
			// here (their early return above protects the fountain).
			if (config.kerb.enabled && !aboveWater) {
				lineCeiling(ctx, x, z, roadTopBlockY, materials);
			}
		}
		VpsPipeline.ROAD_CELLS_WRITTEN.incrementAndGet();

		// ---- kerbs and railings on side edges (5.5)
		applyKerbsAndRailings(net, cell, ctx, config, materials, kerbExtras, roadTopBlockY, writtenWalkSurface2,
				sideFacts, countedEdgeColumns, doorFronts);
	}

	/**
	 * Lines the contiguous run of blocks directly above the road block with the
	 * style's kerb material, up to the first air (the run's top). The road block
	 * itself and everything below are untouched.
	 */
	private static void lineCeiling(VpsGenContext ctx, int x, int z, int roadTopBlockY,
			RoadMaterialTable.StyleMaterials materials) {
		int lined = 0;
		for (int y = roadTopBlockY + 1; y <= ctx.getMaxY(); y++) {
			if (!ctx.isSolidAt(x, y, z)) {
				break; // the ceiling run ends
			}
			setBlock(ctx, x, y, z, materials.kerbBlock.defaultBlockState());
			lined++;
		}
		if (lined > 0) {
			ROOF_LINED_BLOCKS.addAndGet(lined);
		}
	}

	/** Support fill from the ground block up to (not including) the road block. */
	private static void fillSupport(VpsGenContext ctx, int x, int z, int groundTopY, int roadTopBlockY,
			RoadMaterialTable.StyleMaterials materials, VillagePathSmoothedConfig config) {
		Block roadBlock = materials.roadBlock;
		if (!"STYLE_ROAD".equals(config.roadSmoothing.fillMaterial)) {
			Block configured = VpsCompat.blockById(config.roadSmoothing.fillMaterial);
			if (configured != null) {
				roadBlock = configured;
			}
		}
		for (int y = groundTopY - 1; y < roadTopBlockY; y++) {
			setBlock(ctx, x, y, z, roadBlock.defaultBlockState());
		}
	}

	/**
	 * Clears the terrain left above the road block, up to the clear cap.
	 *
	 * @param ceilingTopY first air above the contiguous terrain run above the road
	 *        (the upward probe), so a canopy or a floating layer above an open road
	 *        is not razed
	 */
	private static void clearAbove(VpsGenContext ctx, int x, int z, int roadTopBlockY, int ceilingTopY, VillagePathSmoothedConfig config) {
		int clearTop = RoadWritePolicy.clearTopY(roadTopBlockY, ceilingTopY, config.roadSmoothing.maxClearLayers);
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
	 *   <li><b>Kerb (the wall)</b>: scan <b>up</b> from the road block's level
	 *       through air gaps to the side column's height-spectrum top and wrap
	 *       the blocks above the road when they rise more than
	 *       {@code kerb.minDropHeight} above it. The old gate - "the side must be
	 *       solid AT the road level" - is gone: an open edge with a shelf higher
	 *       up is wrapped too, and the wrap skips the air gap and continues to
	 *       the topmost block. The threshold itself is unchanged (user decision
	 *       2026-10-15): a side rising exactly one block still gets no kerb.</li>
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
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras, int roadTopBlockY, int writtenWalkSurface2,
			Map<SideKey, SideFacts> sideFacts, Set<Long> countedEdgeColumns, int[][] doorFronts) {
		int sRoad2 = writtenWalkSurface2;
		for (int dir = 0; dir < 4; dir++) {
			int outX = cell.x + SIDE_DX[dir];
			int outZ = cell.z + SIDE_DZ[dir];
			if (net.cellAt(outX, outZ) >= 0) {
				continue; // road continues this way: no side edge
			}
			SideFacts facts = sideFacts.computeIfAbsent(new SideKey(outX, outZ, roadTopBlockY),
					k -> probeSideFacts(ctx, k.x(), k.z(), k.roadTopBlockY()));
			if (facts == null) {
				continue; // unreadable column: never fatal, skip the details here
			}

			// the side surface the kerb threshold measures against: the column's
			// height-spectrum top when it has any block at/above the road level,
			// the ground below when it is empty up there
			boolean rises = facts.topmostY() != Integer.MIN_VALUE;
			int sSide2 = rises ? 2 * (facts.topmostY() + 1) : 2 * facts.groundTopY();
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
				placeKerbWall(ctx, outX, outZ, roadTopBlockY, facts.topmostY(), materials, kerbExtras);
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
	 * Plan A (user decision 2026-10-15): true when the cell's road block would
	 * land inside a movable building's 3D box {minX,maxX,minY,maxY,minZ,maxZ}.
	 * The Y test keeps a road that passes well below a building's basement (or
	 * well above it) from being skipped.
	 */
	private static boolean insideBuilding(int[][] buildingBoxes, int x, int z, int solvedP) {
		if (buildingBoxes == null) {
			return false;
		}
		int blockY = RoadWritePolicy.roadTopBlockY(solvedP);
		for (int[] box : buildingBoxes) {
			if (x >= box[0] && x <= box[1] && z >= box[4] && z <= box[5]
					&& blockY >= box[2] - 1 && blockY <= box[3] + 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Replaces dirt-tag blocks on the side column from one above the road block
	 * up to the column's height-spectrum top, skipping air gaps (user decision
	 * 2026-10-15; Q07 replaceable set unchanged). The block level with the road
	 * block itself is below the wrap's start, so a level side gets nothing and a
	 * rising side keeps its bottom course vanilla - the wrap is what "keep going
	 * up until the outer cell runs out of blocks" asks for, from the first layer
	 * above the walk surface.
	 */
	private static void placeKerbWall(VpsGenContext ctx, int x, int z, int roadTopBlockY, int topmostY,
			RoadMaterialTable.StyleMaterials materials, List<Block> kerbExtras) {
		boolean placed = false;
		for (int y = roadTopBlockY + 1; y <= Math.min(ctx.getMaxY(), topmostY); y++) {
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

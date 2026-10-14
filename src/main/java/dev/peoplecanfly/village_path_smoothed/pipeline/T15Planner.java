/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsNoiseContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * T1.5 forward decision (plan section 5, B1-B3): runs inside
 * {@code Structure#generate} before the bounding box is cached. Rebuilds the
 * road network from the assembled pieces with a pure-noise probe, solves the
 * smooth profile, then shifts every RIGID building component vertically so its
 * base follows the road profile (D5=a), syncing jigsaw junctions, guarding
 * against overlaps and clamping to the world height.
 *
 * Pure-noise and deterministic: the same seed always produces the same shift.
 * The resulting solution is cached on the StructureStart and consumed at
 * feature placement.
 */
public final class T15Planner {
	/**
	 * Villages whose road network was re-collected and re-solved after the
	 * building shift, so the noise probe compensates the post-move beard boxes
	 * (review 4 section 7, P1).
	 */
	public static final java.util.concurrent.atomic.AtomicLong T15_RESOLVED_AFTER_SHIFT =
			new java.util.concurrent.atomic.AtomicLong();
	/**
	 * Fix plan v4 D10: cells whose solved surface changed between the first solve
	 * and the post-shift re-solve. The building shift is applied once and the road
	 * re-solved once (a second iteration could cycle: road -> building -> road), so
	 * the road and the buildings are consistent only up to this much. It is a
	 * known residual, recorded rather than iterated away.
	 */
	public static final java.util.concurrent.atomic.AtomicLong T15_RESOLVE_DELTA_CELLS =
			new java.util.concurrent.atomic.AtomicLong();

	private T15Planner() {
	}

	public static VpsVillageSolution plan(
			StructureStart start,
			ChunkGenerator generator,
			RandomState randomState,
			StructureTemplateManager templateManager,
			ChunkPos chunkPos,
			LevelHeightAccessor heightAccessor,
			VillagePathSmoothedConfig config
	) {
		// Fix plan v2, R1 precondition (§7.5.4 finding 1): the T1.5 path used to
		// discard the SolveStats and never touched VILLAGES_SOLVED, so
		// step violations / worst step / villages solved were dead counters in
		// every T1.5-solved world (the default stage). Measure and accumulate
		// here, mirroring VpsPipeline#solveVillage.
		long begin = System.nanoTime();
		try {
			return planInner(start, generator, randomState, templateManager, chunkPos, heightAccessor, config);
		} finally {
			VpsPipeline.SOLVE_NANOS.addAndGet(System.nanoTime() - begin);
		}
	}

	private static VpsVillageSolution planInner(
			StructureStart start,
			ChunkGenerator generator,
			RandomState randomState,
			StructureTemplateManager templateManager,
			ChunkPos chunkPos,
			LevelHeightAccessor heightAccessor,
			VillagePathSmoothedConfig config
	) {
		VpsNoiseContext ctx = new VpsNoiseContext(generator, randomState, heightAccessor);
		RoadNetwork network = RoadCollector.collect(start, ctx, config);
		if (config.roadSmoothing.enabled) {
			VpsPipeline.accumulateSolveStats(RoadSmoother.solve(network, VpsPipeline.paramsFor(config)));
		}
		VpsVillageSolution solution = new VpsVillageSolution(network);
		solution.solved = true;
		solution.t15Applied = true;
		VpsPipeline.VILLAGES_SOLVED.incrementAndGet();

		if (!config.roadSmoothing.enabled) {
			return solution;
		}

		List<StructurePiece> pieces = start.getPieces();
		List<BuildingComponents.Component> components = BuildingComponents.group(pieces);
		if (components.isEmpty()) {
			return solution;
		}

		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();
		int maxShift = config.roadSmoothing.maxBuildingShift;
		List<int[]> shifted = new ArrayList<>();

		for (BuildingComponents.Component component : components) {
			int desired = desiredShift(network, component);
			if (desired == Integer.MIN_VALUE) {
				continue; // no road access / unserved: left where it is
			}
			desired = clampWorldHeight(pieces, component, desired, minY, maxY);
			if (maxShift > 0) {
				desired = Math.max(-maxShift, Math.min(maxShift, desired));
			}
			int chosen = resolveOverlap(pieces, component, desired);
			if (chosen != desired) {
				VpsPipeline.OVERLAP_GUARDS.incrementAndGet();
			}
			component.dy = chosen;
			if (chosen != 0) {
				for (int idx : component.pieceIndices) {
					pieces.get(idx).move(0, chosen, 0);
					VpsPipeline.BUILDINGS_SHIFTED.incrementAndGet();
				}
				shifted.add(new int[]{component.minX, component.maxX, component.minZ, component.maxZ, component.baseY + chosen});
			}
		}
		VpsPipeline.BUILDING_COMPONENTS.addAndGet(components.size());
		syncJunctions(pieces, components);
		solution.shiftedComponents = shifted.toArray(new int[0][]);

		// Review 4 section 7 (P1): close the probe gap that the move just opened.
		// The road profile was solved against beard boxes taken BEFORE the shift,
		// but a moved building has a different bounding box, so the noise probe
		// compensated the wrong columns - the +/-$1 near shifted buildings that
		// the review measured. RoadCollector#collect reads the boxes live off the
		// pieces, so re-running it after the move is enough: no second probe
		// implementation, no state to keep in sync. The building shift itself is
		// NOT repeated (a second pass would be circular: the road moved, so the
		// buildings would move, so the road would move); this only corrects the
		// road, which is what the write stage consumes.
		//
		// Nothing moved -> nothing to correct. The re-collect is a full second
		// RoadCollector#collect plus a full second RoadSmoother#solve, i.e. the
		// single most expensive thing this planner does, and when `shifted` is
		// empty every piece box, every beard box and therefore the whole road
		// network is bit-for-bit what the first pass already produced. Most
		// villages never shift anything, so this guard removes the second solve
		// from the common path entirely. It is safe rather than merely cheap:
		// solution.network already holds the first pass's network, and nothing
		// between the two passes mutates it when dy == 0 for every component.
		if (shifted.isEmpty()) {
			return solution;
		}

		RoadNetwork resolved = RoadCollector.collect(start, ctx, config);
		if (config.roadSmoothing.enabled) {
			VpsPipeline.accumulateSolveStats(RoadSmoother.solve(resolved, VpsPipeline.paramsFor(config)));
		}
		T15_RESOLVE_DELTA_CELLS.addAndGet(countProfileDelta(network, resolved));
		solution.network = resolved;
		T15_RESOLVED_AFTER_SHIFT.incrementAndGet();

		if (config.diagnostics.dumpRoadNetwork) {
			VillagePathSmoothed.LOGGER.info("[VPS] T1.5 village at {}: {} cells, {} building components, {} shifted, {} cells after the post-shift re-solve",
					chunkPos, network.cells.size(), components.size(), shifted.size(), resolved.cells.size());
		}
		return solution;
	}

	/**
	 * How many cells ended up at a different height after the post-shift re-solve
	 * (fix plan v4 D10). Compared by world position, not by list index: the two
	 * networks are rebuilt from scratch and only the positions are comparable.
	 */
	private static int countProfileDelta(RoadNetwork before, RoadNetwork after) {
		java.util.Map<Long, Integer> previous = new java.util.HashMap<>();
		for (RoadCell cell : before.cells) {
			previous.put(RoadCell.packedXZ(cell.x, cell.z), cell.solvedP);
		}
		int changed = 0;
		for (RoadCell cell : after.cells) {
			Integer old = previous.get(RoadCell.packedXZ(cell.x, cell.z));
			if (old != null && old != cell.solvedP) {
				changed++;
			}
		}
		return changed;
	}

	/**
	 * Vertical shift so the component base meets the median adjacent road walk
	 * surface, delegated to the pure {@link BuildingShift} helper (review 3 T5)
	 * with the diagnostic counters kept here.
	 */
	private static int desiredShift(RoadNetwork network, BuildingComponents.Component component) {
		int[] values = new int[Math.max(1, network.cells.size())];
		int count = 0;
		for (RoadCell cell : network.cells) {
			if (cell.x >= component.minX - 1 && cell.x <= component.maxX + 1
					&& cell.z >= component.minZ - 1 && cell.z <= component.maxZ + 1) {
				values[count++] = cell.solvedP;
			}
		}
		int[] adjacent = count == values.length ? values : java.util.Arrays.copyOf(values, count);
		BuildingShift.ShiftDecision decision = BuildingShift.desiredShift(adjacent, component.baseY);
		switch (decision.reason()) {
			case NO_ROAD:
				VpsPipeline.BUILDINGS_NO_ROAD_ACCESS.incrementAndGet();
				return BuildingShift.DO_NOT_MOVE;
			case SPAN_TOO_WIDE:
				VpsPipeline.BUILDINGS_UNSERVED.incrementAndGet();
				return BuildingShift.DO_NOT_MOVE;
			default:
				if (decision.doorParityOdd()) {
					// B3-4: an odd median means the door aligns to a half block. The
					// optional third (hard-boundary) solve pass that would force an even
					// door cell is not run, so count it as a parity fallback.
					VpsPipeline.DOOR_PARITY_FALLBACK.incrementAndGet();
				}
				return decision.shift();
		}
	}

	private static int clampWorldHeight(List<StructurePiece> pieces, BuildingComponents.Component component, int dy, int minY, int maxY) {
		int loDy = Integer.MIN_VALUE;
		int hiDy = Integer.MAX_VALUE;
		for (int idx : component.pieceIndices) {
			BoundingBox box = pieces.get(idx).getBoundingBox();
			loDy = Math.max(loDy, minY - box.minY());
			hiDy = Math.min(hiDy, maxY - box.maxY());
		}
		if (loDy > hiDy) {
			return 0;
		}
		return Math.max(loDy, Math.min(hiDy, dy));
	}

	/** Reduces the shift toward zero until the moved component no longer intersects any other piece. */
	private static int resolveOverlap(List<StructurePiece> pieces, BuildingComponents.Component component, int dy) {
		int candidate = dy;
		while (candidate != 0 && overlaps(pieces, component, candidate)) {
			candidate += candidate > 0 ? -1 : 1;
		}
		return candidate;
	}

	private static boolean overlaps(List<StructurePiece> pieces, BuildingComponents.Component component, int dy) {
		for (int idx : component.pieceIndices) {
			BoundingBox box = pieces.get(idx).getBoundingBox();
			int minX = box.minX();
			int maxX = box.maxX();
			int minY = box.minY() + dy;
			int maxY = box.maxY() + dy;
			int minZ = box.minZ();
			int maxZ = box.maxZ();
			for (int other = 0; other < pieces.size(); other++) {
				if (component.pieceIndices.contains(other)) {
					continue;
				}
				BoundingBox o = pieces.get(other).getBoundingBox();
				if (minX <= o.maxX() && maxX >= o.minX()
						&& minY <= o.maxY() && maxY >= o.minY()
						&& minZ <= o.maxZ() && maxZ >= o.minZ()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * F3: {@code PoolElementStructurePiece#move} does not touch its junctions,
	 * so a shifted component's own junctions and their paired counterparts
	 * (opposite deltaY, source within 1 block, on a piece that did not move)
	 * must be rebuilt with the shifted ground Y. JigsawJunction fields are
	 * final, so new objects are created.
	 */
	private static void syncJunctions(List<StructurePiece> pieces, List<BuildingComponents.Component> components) {
		Map<JigsawJunction, Integer> dyByIdentity = new IdentityHashMap<>();
		List<JigsawJunction> all = new ArrayList<>();
		for (StructurePiece piece : pieces) {
			if (piece instanceof PoolElementStructurePiece pool) {
				all.addAll(pool.getJunctions());
			}
		}
		for (BuildingComponents.Component component : components) {
			if (component.dy == 0) {
				continue;
			}
			for (int idx : component.pieceIndices) {
				StructurePiece piece = pieces.get(idx);
				if (piece instanceof PoolElementStructurePiece pool) {
					for (JigsawJunction junction : pool.getJunctions()) {
						dyByIdentity.put(junction, component.dy);
					}
				}
			}
		}
		if (dyByIdentity.isEmpty()) {
			return;
		}
		// pair the counterpart junctions of pieces that did not move
		for (JigsawJunction junction : all) {
			if (dyByIdentity.containsKey(junction)) {
				continue;
			}
			for (Map.Entry<JigsawJunction, Integer> moved : dyByIdentity.entrySet()) {
				if (pairs(junction, moved.getKey())) {
					dyByIdentity.put(junction, moved.getValue());
					break;
				}
			}
		}
		for (StructurePiece piece : pieces) {
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				continue;
			}
			List<JigsawJunction> junctions = pool.getJunctions();
			boolean touched = false;
			for (JigsawJunction junction : junctions) {
				if (dyByIdentity.containsKey(junction)) {
					touched = true;
					break;
				}
			}
			if (!touched) {
				continue;
			}
			List<JigsawJunction> rebuilt = new ArrayList<>(junctions.size());
			for (JigsawJunction junction : junctions) {
				Integer dy = dyByIdentity.get(junction);
				if (dy == null || dy == 0) {
					rebuilt.add(junction);
				} else {
					rebuilt.add(new JigsawJunction(junction.getSourceX(), junction.getSourceGroundY() + dy,
							junction.getSourceZ(), junction.getDeltaY(), junction.getDestProjection()));
				}
			}
			junctions.clear();
			for (JigsawJunction junction : rebuilt) {
				pool.addJunction(junction);
			}
		}
	}

	private static boolean pairs(JigsawJunction a, JigsawJunction b) {
		return a.getDeltaY() == -b.getDeltaY()
				&& Math.abs(a.getSourceX() - b.getSourceX()) <= 1
				&& Math.abs(a.getSourceZ() - b.getSourceZ()) <= 1;
	}

}

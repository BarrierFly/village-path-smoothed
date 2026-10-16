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
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.Arrays;
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
	/**
	 * Plan 2026-10-16: RIGID decor pieces (lamp/tree/hay) shifted so they keep
	 * standing exactly on their seat's solved height. A lamp is a RIGID
	 * single-pool piece and used to be aligned as a singleton "building"; that
	 * alignment is replaced by the seat's dy.
	 */
	public static final java.util.concurrent.atomic.AtomicLong SEAT_DECOR_PIECES_MOVED =
			new java.util.concurrent.atomic.AtomicLong();
	/**
	 * R1-4 (four-symptom fix plan 2026-10-16): seat columns whose decor piece
	 * would leave the world bounds, so {@code settleSeats} clamps the seat's
	 * solved level to the decor's reachable height. Counted only (the fix is a
	 * pre-solve pin, deferred until the edge-village frequency is known).
	 */
	public static final java.util.concurrent.atomic.AtomicLong SEAT_EDGE_CLAMPED =
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
			// plan C phase 1: the plaza groups solve flat at the solver's own level
			// (the group's median terrain height); the roads meet them via the
			// normal seam constraints
			network.applyPlazaPins(true);
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
		// Decor pieces standing on a mask seat (a lamp or a hay pile singleton)
		// ride the seat's solved level instead of aligning on their own (plan
		// 2026-10-16)
		java.util.Set<Long> seatColumns = new java.util.HashSet<>();
		for (RoadCell cell : network.cells) {
			if (cell.seat) {
				seatColumns.add(RoadCell.packedXZ(cell.x, cell.z));
			}
		}
		List<BuildingComponents.Component> components = BuildingComponents.group(pieces);
		components.removeIf(component -> isSeatRider(component, pieces, seatColumns));
		// F-C: the template's real door blocks, loaded once per plan and shared by
		// the alignment, the door fronts and the offset counter below
		Map<Integer, int[][]> doorsByPiece = BuildingComponents.templateDoors(pieces, templateManager);
		countDoorAnchorOffsets(pieces, components, doorsByPiece);

		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();
		if (components.isEmpty()) {
			settleSeats(solution.network, pieces, minY, maxY, config);
			return solution;
		}

		int maxShift = config.roadSmoothing.maxBuildingShift;
		List<int[]> shifted = new ArrayList<>();

		for (BuildingComponents.Component component : components) {
			int desired = desiredShift(network, component, pieces, doorsByPiece, config);
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

		// plan C: move each plaza group onto the level the solver pinned it to,
		// so the rigid platform (and the well rim / fountain riding on it) renders
		// exactly the solved plaza height
		List<BuildingComponents.Component> plazaComponents = shiftPlazaGroups(network, pieces, minY, maxY, maxShift);

		List<BuildingComponents.Component> allShifted = new ArrayList<>(components.size() + plazaComponents.size());
		allShifted.addAll(components);
		allShifted.addAll(plazaComponents);
		syncJunctions(pieces, allShifted);
		solution.shiftedComponents = shifted.toArray(new int[0][]);
		// read after the shift: the boxes must describe where the buildings really
		// are, because the writer uses them to refuse a road column (plan A).
		solution.buildingBoxes = BuildingComponents.protectedBoxes(pieces);
		// R3-1 (2026-10-16): the front direction reads the road network; the cell
		// POSITIONS are identical in phase 1 and the phase-2 re-solve, so the
		// first network is enough and the mismatch is counted exactly once.
		solution.doorFronts = BuildingComponents.doorFronts(pieces, doorsByPiece, network);

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
		// R1-1 (2026-10-16): "nothing moved" is not enough to skip the phase-2
		// re-solve. A plaza group whose dy the overlap/world guard pushed back to
		// zero leaves its phase-1 pin at the noise median, but the rigid platform
		// still renders at its vanilla level (plazaVanillaP) - the two disagree,
		// so the second collect+solve is exactly what corrects the plaza edge.
		if (shifted.isEmpty() && !plazaMoved(plazaComponents) && !plazaPinsMismatch(network)) {
			settleSeats(solution.network, pieces, minY, maxY, config);
			solution.doorSigns = computeDoorSigns(components, pieces, doorsByPiece, solution.network);
			return solution;
		}

		RoadNetwork resolved = RoadCollector.collect(start, ctx, config);
		if (config.roadSmoothing.enabled) {
			// plan C phase 2: pin every plaza group at the level its pieces really
			// render at after the move - the solve and the world agree by construction
			resolved.applyPlazaPins(false);
			VpsPipeline.accumulateSolveStats(RoadSmoother.solve(resolved, VpsPipeline.paramsFor(config)));
		}
		T15_RESOLVE_DELTA_CELLS.addAndGet(countProfileDelta(network, resolved));
		solution.network = resolved;
		T15_RESOLVED_AFTER_SHIFT.incrementAndGet();
		settleSeats(resolved, pieces, minY, maxY, config);
		solution.doorSigns = computeDoorSigns(components, pieces, doorsByPiece, resolved);

		if (config.diagnostics.dumpRoadNetwork) {
			VillagePathSmoothed.LOGGER.info("[VPS] T1.5 village at {}: {} cells, {} building components, {} shifted, {} cells after the post-shift re-solve",
					chunkPos, network.cells.size(), components.size(), shifted.size(), resolved.cells.size());
		}
		return solution;
	}

	/**
	 * Debug door signs (user request 2026-10-16, always on): one standing sign
	 * per template door of every movable component, documenting the door-to-road
	 * alignment on foot - line 1 the door X/Z with the base walk level the
	 * alignment used (baseY + dy + 1, the Y0 layer top), line 2 the
	 * corresponding road cell X/Z with its solved walk surface. Pure data; the
	 * writer places the blocks. Must run on the FINAL network so line 2 prints
	 * the surface the writer will actually render. Doors without a road cell in
	 * the alignment window get no sign; the skip is counted.
	 */
	static int[][] computeDoorSigns(List<BuildingComponents.Component> components, List<StructurePiece> pieces,
			Map<Integer, int[][]> doorsByPiece, RoadNetwork net) {
		List<int[][]> doorsPerComponent = new ArrayList<>(components.size());
		int[] baseWalkY = new int[components.size()];
		for (int c = 0; c < components.size(); c++) {
			BuildingComponents.Component component = components.get(c);
			List<int[]> doors = new ArrayList<>();
			for (int idx : component.pieceIndices) {
				int[][] pieceDoors = doorsByPiece.get(idx);
				if (pieceDoors == null) {
					continue;
				}
				StructurePiece piece = pieces.get(idx);
				var box = piece.getBoundingBox();
				int cx = (box.minX() + box.maxX()) / 2;
				int cz = (box.minZ() + box.maxZ()) / 2;
				for (int[] door : pieceDoors) {
					// R3-1/R4-1 (2026-10-16): the sign stands one step toward the
					// nearest road cell (the same direction the railing exemption
					// uses), falling back to the footprint-centre axis when the
					// network holds no road cell; a door at both no-direction
					// cases keeps the sign in its own column (visibility first)
					int[] axis = DoorSigns.stepTowardRoad(net, door[0], door[1]);
					if (axis == null) {
						axis = BuildingComponents.outwardAxis(door[0], door[1], cx, cz);
					}
					int floorY = door.length > 2 ? door[2] + component.dy : component.baseY + component.dy;
					if (axis != null) {
						doors.add(new int[]{door[0], door[1], axis[0], axis[1], floorY});
					} else {
						doors.add(new int[]{door[0], door[1], 0, 0, floorY});
					}
				}
			}
			doorsPerComponent.add(doors.toArray(new int[0][]));
			baseWalkY[c] = component.baseY + component.dy + BuildingShift.DOOR_WALK_OFFSET;
		}
		int[][] signs = DoorSigns.compute(doorsPerComponent, baseWalkY, net);
		int doorCount = 0;
		for (int[][] doors : doorsPerComponent) {
			doorCount += doors.length;
		}
		VpsPipeline.DOOR_SIGNS_WITHOUT_ROAD.addAndGet(doorCount - signs.length);
		return signs;
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
	 * Plan C (user decision 2026-10-15): moves each plaza GROUP's pieces so the
	 * rigid platform renders the level the solver pinned the group to. One dy
	 * per group - the pieces of a group share the assembled height in practice -
	 * computed from the group's median piece minY; the world-height, shift-cap
	 * and overlap guards are the building rules (a building always wins, a plaza
	 * group is not an obstacle to another group's move). A piece claimed by two
	 * groups moves once (first group wins); the phase-2 pin re-reads the
	 * reality, so the leftover mismatch is consistent, never silent.
	 */
	private static List<BuildingComponents.Component> shiftPlazaGroups(RoadNetwork network, List<StructurePiece> pieces,
			int minY, int maxY, int maxShift) {
		List<BuildingComponents.Component> out = new ArrayList<>();
		if (network.plazaGroups.isEmpty()) {
			return out;
		}
		java.util.Set<Integer> movedPieces = new java.util.HashSet<>();
		for (int[] group : network.plazaGroups) {
			int level = network.cells.get(group[0]).plazaPinP; // one flat level per group
			List<Integer> pieceIndices = new ArrayList<>();
			for (int cellIdx : group) {
				RoadCell cell = network.cells.get(cellIdx);
				if (cell.pieceIndex >= 0 && !pieceIndices.contains(cell.pieceIndex) && !movedPieces.contains(cell.pieceIndex)) {
					pieceIndices.add(cell.pieceIndex);
				}
			}
			if (pieceIndices.isEmpty()) {
				continue; // every piece of this group already moved with an earlier group
			}
			VpsPipeline.PLAZA_GROUPS.incrementAndGet();
			BuildingComponents.Component component = new BuildingComponents.Component();
			component.pieceIndices.addAll(pieceIndices);
			int[] baseYs = new int[pieceIndices.size()];
			for (int k = 0; k < baseYs.length; k++) {
				baseYs[k] = pieces.get(pieceIndices.get(k)).getBoundingBox().minY();
			}
			Arrays.sort(baseYs);
			int baseY = baseYs[baseYs.length / 2];
			component.baseY = baseY;
			// the pin is a walk surface in half units (always even); the platform
			// block layer that renders it sits at level / 2 - 1
			int requested = level / 2 - 1 - baseY;
			int dy = clampWorldHeight(pieces, component, requested, minY, maxY);
			if (maxShift > 0) {
				dy = Math.max(-maxShift, Math.min(maxShift, dy));
			}
			dy = resolveOverlap(pieces, component, dy);
			if (dy != requested) {
				// R1-1: the guard (overlap or world height) pushed the group off the
				// level the solver pinned it to - the phase-2 re-pin must run.
				VpsPipeline.PLAZA_OVERLAP_GUARDS.incrementAndGet();
			}
			component.dy = dy;
			if (dy != 0) {
				for (int idx : component.pieceIndices) {
					pieces.get(idx).move(0, dy, 0);
					movedPieces.add(idx);
					VpsPipeline.PLAZA_PIECES_SHIFTED.incrementAndGet();
				}
				VpsPipeline.PLAZAS_SHIFTED.incrementAndGet();
			}
			out.add(component);
		}
		return out;
	}

	/** True when at least one plaza group actually moved. */
	private static boolean plazaMoved(List<BuildingComponents.Component> plazaComponents) {
		for (BuildingComponents.Component component : plazaComponents) {
			if (component.dy != 0) {
				return true;
			}
		}
		return false;
	}

	/**
	 * R1-1 (2026-10-16): true when any plaza group's phase-1 pin - the median of
	 * the noise-probed terrain set by {@code applyPlazaPins(true)} - differs from
	 * the level the owning rigid pieces really render at ({@code plazaVanillaP},
	 * read live off the boxes). A group whose dy the guard pushed back to zero
	 * keeps its phase-1 noise median but the platform still renders at its vanilla
	 * level, so the first solve pinned the plaza to the wrong height; only the
	 * phase-2 re-pin (which follows the moved pieces) corrects it.
	 */
	private static boolean plazaPinsMismatch(RoadNetwork network) {
		for (int[] group : network.plazaGroups) {
			for (int idx : group) {
				RoadCell cell = network.cells.get(idx);
				if (cell.plazaPinned && cell.plazaPinP != cell.plazaVanillaP) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Road heights the component should align to: the road cells nearest its
	 * <b>door</b>, i.e. the front where it connects to the street network (user
	 * decision 2026-10-14).
	 *
	 * <p>The old rule took the median of every road cell within the footprint
	 * plus one ring, so a long house or a farm next to a plaza was pulled to a
	 * road several blocks away and ended up off its own doorstep. Now:
	 *
	 * <ol>
	 *   <li>the door is the component's <b>template door block</b> - the lower
	 *       door halves read from the piece's template (F-C, 2026-10-15: the
	 *       junction sits 2-7 blocks from the door on 53 of the 120 vanilla
	 *       house templates, so anchoring on it left the door-side road
	 *       unconstrained - the measured "road higher than the door" symptom);</li>
	 *   <li>each door contributes exactly <b>one</b> road cell: the cell nearest
	 *       the door by the <b>horizontal Manhattan distance</b>
	 *       ({@code |dx| + |dz|}), searched inside the footprint plus
	 *       {@link #DOOR_SEARCH_PAD}. The old rule took every cell at the minimum
	 *       Chebyshev distance, which is what let a diagonal cell win and pulled
	 *       the alignment off the orthogonal doorway (R4-1, user decision
	 *       2026-10-16);</li>
	 *   <li>the component's shift is then decided over the one height per door
	 *       (multiple doors still resolve through the median/interval rule);</li>
	 *   <li>with no template door (a component that only touches other
	 *       buildings, or a farm/pen without door blocks) the connection
	 *       junctions are the fallback, and with no junction at all the
	 *       footprint centre's nearest road cell is used instead.</li>
	 * </ol>
	 */
	private static int[] adjacentRoadHeights(RoadNetwork network, BuildingComponents.Component component,
			List<StructurePiece> pieces, Map<Integer, int[][]> doorsByPiece) {
		int[] window = {
				component.minX - DOOR_SEARCH_PAD, component.maxX + DOOR_SEARCH_PAD,
				component.minZ - DOOR_SEARCH_PAD, component.maxZ + DOOR_SEARCH_PAD
		};
		int[][] doors = doorPoints(component, pieces, doorsByPiece);
		List<Integer> values = new ArrayList<>();
		if (doors != null) {
			for (int[] door : doors) {
				RoadCell road = DoorSigns.nearestRoadCell(network, door[0], door[1], window);
				if (road != null) {
					values.add(road.solvedP);
				}
			}
		} else {
			int cx = (component.minX + component.maxX) / 2;
			int cz = (component.minZ + component.maxZ) / 2;
			RoadCell road = DoorSigns.nearestRoadCell(network, cx, cz, window);
			if (road != null) {
				values.add(road.solvedP);
			}
		}
		if (values.isEmpty()) {
			return null;
		}
		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	/** How far past the footprint a door's road cell may sit and still count. */
	private static final int DOOR_SEARCH_PAD = 4;

	/**
	 * Door positions {x, z, floorY} of one component: the template's lower door
	 * halves (F-C), or - when the component carries none - its non-RIGID
	 * connection junctions (the pre-F-C rule, kept as the fallback for farms,
	 * pens and unloadable templates).
	 */
	private static int[][] doorPoints(BuildingComponents.Component component, List<StructurePiece> pieces,
			Map<Integer, int[][]> doorsByPiece) {
		List<int[]> doors = new ArrayList<>();
		for (int idx : component.pieceIndices) {
			int[][] pieceDoors = doorsByPiece.get(idx);
			if (pieceDoors != null) {
				for (int[] door : pieceDoors) {
					doors.add(door);
				}
			}
		}
		if (!doors.isEmpty()) {
			return doors.toArray(new int[0][]);
		}
		for (int idx : component.pieceIndices) {
			StructurePiece piece = pieces.get(idx);
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				continue;
			}
			for (JigsawJunction junction : pool.getJunctions()) {
				if (junction.getDestProjection() != StructureTemplatePool.Projection.RIGID) {
					doors.add(new int[]{junction.getSourceX(), junction.getSourceZ()});
				}
			}
		}
		return doors.isEmpty() ? null : doors.toArray(new int[0][]);
	}

	/**
	 * Vertical shift so the component's Y0 layer top (the interior floor) meets
	 * the solid levels of the adjacent road cells, delegated to the pure
	 * {@link BuildingShift} helper (review 3 T5, plan D interval rework and
	 * plan E slab-as-air targets, 2026-10-15) with the diagnostic counters kept
	 * here. The optional median two-wing limit ({@code
	 * buildingShiftMedianWingBlocks}, 0 = off) clamps the chosen shift around
	 * the legacy median scheme's shift.
	 */
	private static int desiredShift(RoadNetwork network, BuildingComponents.Component component, List<StructurePiece> pieces,
			Map<Integer, int[][]> doorsByPiece, VillagePathSmoothedConfig config) {
		int[] adjacent = adjacentRoadHeights(network, component, pieces, doorsByPiece);
		BuildingShift.ShiftDecision decision = BuildingShift.desiredShift(adjacent, component.baseY);
		switch (decision.reason()) {
			case NO_ROAD:
				VpsPipeline.BUILDINGS_NO_ROAD_ACCESS.incrementAndGet();
				return BuildingShift.DO_NOT_MOVE;
			default: {
				int shift = decision.shift();
				int wing = config.roadSmoothing.buildingShiftMedianWingBlocks;
				if (wing > 0) {
					shift = Math.max(decision.medianShift() - wing, Math.min(decision.medianShift() + wing, shift));
				}
				if (decision.doorParityOdd()) {
					// B3-4: the MEDIAN FALLBACK aligned the door to a slab level. Since
					// plan E (slab-as-air targets, 2026-10-15) every target is a whole
					// block, so this can no longer fire; the counter is kept so a
					// regression to raw-slab targets stays visible.
					VpsPipeline.DOOR_PARITY_FALLBACK.incrementAndGet();
				}
				if (!decision.viaInterval()) {
					// R2-C (2026-10-16): the door road cells span more than the
					// half-block tolerance can bridge, so the shift came from the
					// median fallback. Measured only; the minimax switch waits for
					// the real-machine frequency.
					VpsPipeline.ALIGN_INTERVAL_FALLBACK.incrementAndGet();
				}
				return shift;
			}
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

	/**
	 * Reduces the shift toward zero until the moved component no longer
	 * <b>strictly intersects</b> any other movable (building) piece.
	 *
	 * <p>User decision 2026-10-14: only movable pieces count. Comparing against
	 * every piece (the old rule) meant a street or terminator piece - whose
	 * bounding box is one block tall but sits exactly along the road edge the
	 * building opens onto - was hit by almost any vertical move, so the guard
	 * walked the shift all the way back to zero and the building stayed at the
	 * vanilla terrain height. Interpenetrating a street with a correctly aligned
	 * building is not a defect; interpenetrating another building is.
	 *
	 * <p>F-A (door two-symptom recheck 2026-10-15): the AABB test is a STRICT
	 * intersection. The old {@code <=}/{@code >=} comparison counted touching
	 * boxes as overlapping, so any movable neighbour flush against the building
	 * (even a 1x1 hay pile) could pin the whole component at the vanilla
	 * position - the alignment then lost by the slope's 1-2 blocks.
	 */
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
				if (!BuildingComponents.isMovable(pieces.get(other))) {
					continue; // streets, terminators and decor: not an obstacle (user decision 2026-10-14)
				}
				BoundingBox o = pieces.get(other).getBoundingBox();
				// F-A: strict intersection - touching boxes are not an overlap
				if (minX < o.maxX() && maxX > o.minX()
						&& minY < o.maxY() && maxY > o.minY()
						&& minZ < o.maxZ() && maxZ > o.minZ()) {
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

	/**
	 * True when the component is a single 1x1-footprint piece standing on a mask
	 * seat column: it rides the seat's solved level (settleSeats) instead of
	 * aligning on its own. A lamp is a RIGID single-pool piece and used to be
	 * treated as a singleton building here. Since 2026-10-16 hay piles are not
	 * building candidates at all (a standalone pile never moves), so the 1x1
	 * RIGID lamp-like piece is the remaining case; a hay pile on a seat still
	 * moves exactly once - with its seat (user decision 2026-10-16).
	 */
	private static boolean isSeatRider(BuildingComponents.Component component, List<StructurePiece> pieces,
			java.util.Set<Long> seatColumns) {
		if (component.pieceIndices.size() != 1) {
			return false;
		}
		BoundingBox box = pieces.get(component.pieceIndices.get(0)).getBoundingBox();
		if (box.minX() != box.maxX() || box.minZ() != box.maxZ()) {
			return false;
		}
		return seatColumns.contains(RoadCell.packedXZ(box.minX(), box.minZ()));
	}

	/**
	 * Moves the RIGID decor piece standing on every solved seat (plan
	 * 2026-10-16): the seat rides the solved profile, so the lamp/tree/hay on
	 * it must shift by the same whole blocks to keep its shape intact. The
	 * decor pieces are the 1x1-footprint non-street pool elements at the seat's
	 * column; their own alignment is replaced by the seat's dy. The junction a
	 * lamp carries is deliberately NOT re-synced: it records the STREET side's
	 * jigsaw ground level, and the street did not move.
	 */
	private static void settleSeats(RoadNetwork network, List<StructurePiece> pieces, int minY, int maxY,
			VillagePathSmoothedConfig config) {
		for (RoadCell cell : network.cells) {
			if (!cell.seat) {
				continue;
			}
			// the rendered seat top: a full block at solvedP/2 - 1 (solvedP is
			// even by the seat's parity constraint; an odd residual value floors)
			int targetWalk = cell.solvedP / 2;
			for (StructurePiece piece : pieces) {
				if (!(piece instanceof PoolElementStructurePiece pool)) {
					continue;
				}
				if (!(pool.getElement() instanceof SinglePoolElement)
						&& !(pool.getElement() instanceof FeaturePoolElement)) {
					continue; // villager/cat spawn points place no blocks
				}
				if (RoadCollector.isStreetPiece(piece, config)) {
					continue; // streets/terminators/centres are rewritten per column
				}
				BoundingBox box = piece.getBoundingBox();
				if (box.minX() != cell.x || box.maxX() != cell.x
						|| box.minZ() != cell.z || box.maxZ() != cell.z) {
					continue; // not a 1x1 piece on this seat's column
				}
				int dy = targetWalk - box.minY();
				if (dy == 0) {
					continue;
				}
				int clamped = Math.max(minY - box.minY(), Math.min(maxY - box.maxY(), dy));
				if (clamped != dy) {
					// the world edge wins: pull the seat's solved level to the
					// decor's reachable height (the phase-2 plaza precedent: the
					// pieces are the rigid truth), so the two never disagree.
					// R1-4 (2026-10-16): counted only - the clean fix (pin the seat
					// before the solve) waits for the edge-village frequency.
					SEAT_EDGE_CLAMPED.incrementAndGet();
					cell.solvedP = (box.minY() + clamped) * 2;
				}
				piece.move(0, clamped, 0);
				SEAT_DECOR_PIECES_MOVED.incrementAndGet();
			}
		}
	}

	/**
	 * F-D (door two-symptom recheck 2026-10-15): the Chebyshev distance from
	 * every non-RIGID connection junction to the nearest template door block of
	 * its component, bucketed into /vps stats. This is the distribution that
	 * made candidate C visible - 53 of the 120 vanilla house templates sit at
	 * offset 2 or more, and every one of them used to align on the wrong side.
	 * Components without template doors (farms, pens) contribute nothing.
	 */
	private static void countDoorAnchorOffsets(List<StructurePiece> pieces, List<BuildingComponents.Component> components,
			Map<Integer, int[][]> doorsByPiece) {
		for (BuildingComponents.Component component : components) {
			List<int[]> doors = new ArrayList<>();
			for (int idx : component.pieceIndices) {
				int[][] pieceDoors = doorsByPiece.get(idx);
				if (pieceDoors != null) {
					for (int[] door : pieceDoors) {
						doors.add(door);
					}
				}
			}
			if (doors.isEmpty()) {
				continue;
			}
			for (int idx : component.pieceIndices) {
				StructurePiece piece = pieces.get(idx);
				if (!(piece instanceof PoolElementStructurePiece pool)) {
					continue;
				}
				for (JigsawJunction junction : pool.getJunctions()) {
					if (junction.getDestProjection() == StructureTemplatePool.Projection.RIGID) {
						continue;
					}
					int best = Integer.MAX_VALUE;
					for (int[] door : doors) {
						best = Math.min(best, Math.max(Math.abs(junction.getSourceX() - door[0]), Math.abs(junction.getSourceZ() - door[1])));
					}
					if (best == Integer.MAX_VALUE) {
						continue;
					}
					switch (Math.min(best, 4)) {
						case 0 -> VpsPipeline.DOOR_ANCHOR_OFFSET_0.incrementAndGet();
						case 1 -> VpsPipeline.DOOR_ANCHOR_OFFSET_1.incrementAndGet();
						case 2 -> VpsPipeline.DOOR_ANCHOR_OFFSET_2.incrementAndGet();
						case 3 -> VpsPipeline.DOOR_ANCHOR_OFFSET_3.incrementAndGet();
						default -> VpsPipeline.DOOR_ANCHOR_OFFSET_4_PLUS.incrementAndGet();
					}
				}
			}
		}
	}

}

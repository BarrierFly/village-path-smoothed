/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsNoiseContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
			VillagePathSmoothedConfig config,
			RegistryAccess registries
	) {
		// Fix plan v2, R1 precondition (§7.5.4 finding 1): the T1.5 path used to
		// discard the SolveStats and never touched VILLAGES_SOLVED, so
		// step violations / worst step / villages solved were dead counters in
		// every T1.5-solved world (the default stage). Measure and accumulate
		// here, mirroring VpsPipeline#solveVillage.
		long begin = System.nanoTime();
		try {
			return planInner(start, generator, randomState, templateManager, chunkPos, heightAccessor, config, registries);
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
			VillagePathSmoothedConfig config,
			RegistryAccess registries
	) {
		VpsNoiseContext ctx = new VpsNoiseContext(generator, randomState, heightAccessor);
		RoadNetwork network = RoadCollector.collect(start, ctx, config);
		// Audit 2026-10-20: the decor pieces are recognised here and their actual
		// blocks are what plan A/the writer protect. The set of pieces is stable
		// across the vertical shifts; the block positions are refreshed below.
		StreetDecor.Resolved decor = StreetDecor.identify(start.getPieces(), network, templateManager, registries);
		network = StreetDecor.addFixtureBases(network, decor, config.roadSmoothing.maxExactSectionWidth, true);
		VpsPipeline.STREET_DECOR_PIECES.addAndGet(decor.pieces.size());
		VpsPipeline.STREET_DECOR_BLOCKS.addAndGet(decor.blocks.size());
		if (config.roadSmoothing.enabled) {
			// plan C phase 1: the plaza groups solve flat at the solver's own level
			// (the group's median terrain height); the roads meet them via the
			// normal seam constraints. The group pins are still movable here - no
			// plaza piece has been placed yet - so the polish may shift a whole
			// group by whole blocks when a seam would otherwise be unsatisfiable.
			network.applyPlazaPins(true);
			network.plazaPinsMovable = true;
			VpsPipeline.accumulateSolveStats(RoadSmoother.solve(network, VpsPipeline.paramsFor(config)));
		}
		VpsVillageSolution solution = new VpsVillageSolution(network);
		solution.solved = true;
		solution.t15Applied = true;
		VpsPipeline.VILLAGES_SOLVED.incrementAndGet();

		if (!config.roadSmoothing.enabled) {
			solution.buildingBoxes = BuildingComponents.protectedBoxes(start.getPieces(), decor.pieceIndices);
			solution.decorBlocks = decor.blocks;
			solution.decorBaseBlocks = decor.baseBlocks;
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
		// Door/basis fix plan 2026-10-17: real doors only, and the
		// building_entrance jigsaw that selects the main door.
		BuildingComponents.TemplateScan scan = BuildingComponents.scanTemplates(pieces, templateManager);
		countDoorAnchorOffsets(pieces, components, scan);

		int minY = ctx.getMinY();
		int maxY = ctx.getMaxY();
		if (components.isEmpty()) {
			settleSeats(solution.network, pieces, minY, maxY, config);
			// 1x1 decor moved with its seat in settleSeats; refresh the footprint
			// so the writer protects where the blocks really are.
			decor = StreetDecor.refresh(decor, pieces, templateManager);
			int[][] boxes = BuildingComponents.protectedBoxes(pieces, decor.pieceIndices);
			int[][] exclusion = mergeBoxes(boxes, decor.columnBoxes);
			List<List<DoorSigns.DoorTarget>> targets = collectDoorTargets(components, pieces, scan,
					solution.network, exclusion, true);
			solution.buildingBoxes = boxes;
			solution.decorBlocks = decor.blocks;
			java.util.Map<Long, String> emptyBase = new java.util.HashMap<>(decor.fixtureBaseBlocks);
			emptyBase.putAll(decor.baseBlocks);
			solution.decorBaseBlocks = emptyBase;
			StreetDecor.recordIsolatedSeats(solution.network);
			StreetDecor.recordRoadMismatch(decor, solution.network);
			solution.doorFronts = buildDoorFronts(components, pieces, targets, solution.network);
			solution.doorSigns = computeDoorSigns(components, targets, solution.network, config);
			solution.seamSigns = computeSeamSigns(solution.network, config);
			return solution;
		}

		int maxShift = config.roadSmoothing.maxBuildingShift;
		List<int[]> shifted = new ArrayList<>();
		int[][] firstBoxes = BuildingComponents.protectedBoxes(pieces, decor.pieceIndices);
		int[][] firstExclusion = mergeBoxes(firstBoxes, decor.columnBoxes);
		List<List<DoorSigns.DoorTarget>> firstTargets = new ArrayList<>(components.size());

		for (BuildingComponents.Component component : components) {
			List<DoorSigns.DoorTarget> targets = doorTargets(component, pieces, scan, network, firstExclusion, false);
			firstTargets.add(targets);
			int desired = targets.isEmpty()
					? desiredShiftFromJunctions(network, component, pieces, config)
					: desiredShiftFromTargets(targets, component, config);
			if (desired == BuildingShift.DO_NOT_MOVE) {
				VpsPipeline.BUILDINGS_NO_ROAD_ACCESS.incrementAndGet();
				continue; // no road access / no outward front: left where it is
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
				shifted.add(new int[]{component.minX, component.maxX, component.minZ, component.maxZ,
						component.baseWalkY + chosen});
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

		// Audit 2026-10-20 decision 2: a multi-block decor is a building and the
		// first pass may have moved it. Refresh its footprint at the new position;
		// the final solve pins its seat and footprint there, so the road meets the
		// rigid base instead of the seat drifting away from it.
		StreetDecor.Resolved decorAfterShift = StreetDecor.refresh(decor, pieces, templateManager);

		// Review 4 section 7 (P1): close the probe gap that the move just opened.
		// The road profile was solved against beard boxes taken BEFORE the shift,
		// but a moved building has a different bounding box, so the noise probe
		// compensated the wrong columns - the +/-$1 near shifted buildings that
		// the review measured. RoadCollector#collect reads the boxes live off the
		// pieces, so re-running it after the move is enough: no second probe
		// implementation, no state to keep in sync.
		//
		// R1-1 (2026-10-16): "nothing moved" is not enough to skip the phase-2
		// re-solve. A plaza group whose dy the overlap/world guard pushed back to
		// zero leaves its phase-1 pin at the noise median, but the rigid platform
		// still renders at its vanilla level (plazaVanillaP) - the two disagree,
		// so the second collect+solve is exactly what corrects the plaza edge.
		// Audit 2026-10-21: the final solve also owns the multi-block decor's
		// movable FULL base group, so it must run whenever such a decor exists.
		RoadNetwork finalNetwork = network;
		boolean reSolved = false;
		StreetDecor.Resolved fixtures = decor;
		if (!(shifted.isEmpty() && !plazaMoved(plazaComponents) && !plazaPinsMismatch(network)
				&& !hasMultiBlockDecor(decorAfterShift))) {
			RoadNetwork resolved = RoadCollector.collect(start, ctx, config);
			if (config.roadSmoothing.enabled) {
				// The house/street fixture bases are re-identified at the moved
				// pieces' current positions and pinned in the final solve.
				fixtures = StreetDecor.identify(start.getPieces(), resolved, templateManager, registries);
				resolved = StreetDecor.addFixtureBases(resolved, fixtures, config.roadSmoothing.maxExactSectionWidth, true);
				// plan C phase 2: pin every plaza group at the level its pieces really
				// render at after the move - the solve and the world agree by construction
				resolved.applyPlazaPins(false);
				// Audit 2026-10-21: the decor base is not an immovable pin any more.
				// Its full footprint (seat + actual blocks + air columns over road)
				// is one MOVABLE flat unit; the polish flattens it to one even level
				// and the rigid decor is moved onto that level after the solve.
				StreetDecor.buildBaseGroups(decorAfterShift, resolved);
				VpsPipeline.accumulateSolveStats(RoadSmoother.solve(resolved, VpsPipeline.paramsFor(config)));
			}
			T15_RESOLVE_DELTA_CELLS.addAndGet(countProfileDelta(network, resolved));
			T15_RESOLVED_AFTER_SHIFT.incrementAndGet();
			finalNetwork = resolved;
			reSolved = true;
		}
		solution.network = finalNetwork;

		// D5 (user decision 2026-10-17): settle the seats on the FINAL network
		// first, then run the authoritative second alignment against that network
		// and freeze. Seats may rewrite cell.solvedP at the world edge (R1-4), so
		// they must run before the alignment that consumes those heights.
		settleSeats(finalNetwork, pieces, minY, maxY, config);

		// Audit 2026-10-21: the multi-block decor follows the base group the final
		// solve chose, exactly like a 1x1 lamp follows its seat. This replaces the
		// old immovable pin: the base stays movable in the solve, so the road and
		// the fixture can meet instead of freezing one side.
		StreetDecor.Resolved settledDecor = StreetDecor.settleBases(decorAfterShift, finalNetwork,
				pieces, templateManager, minY, maxY);

		StreetDecor.Resolved finalDecor = settledDecor;

		List<List<DoorSigns.DoorTarget>> finalTargets = new ArrayList<>(components.size());
		for (BuildingComponents.Component component : components) {
			if (isMultiBlockDecorComponent(component, decor)) {
				// Audit 2026-10-21: a pure multi-block decor component already
				// followed its solved base group; the second alignment would move it
				// away from that base. Its dy is final.
				finalTargets.add(List.of());
				continue;
			}
			// Recompute per component: earlier iterations of this loop may have moved
			// a building, and the old code read fresh boxes for exactly this reason.
			int[][] boxes = BuildingComponents.protectedBoxes(pieces, finalDecor.pieceIndices);
			int[][] exclusion = mergeBoxes(boxes, finalDecor.columnBoxes);
			List<DoorSigns.DoorTarget> targets = doorTargets(component, pieces, scan, finalNetwork, exclusion, true);
			finalTargets.add(targets);
			int desired = targets.isEmpty()
					? desiredShiftFromJunctions(finalNetwork, component, pieces, config)
					: desiredShiftFromTargets(targets, component, config);
			// D4 (user decision 2026-10-17): no outward road cell means no
			// alignment. The user confirmed the second pass keeps the first pass's
			// shift in that case (additional = 0) instead of undoing it.
			int additional = BuildingShift.additionalShift(desired, component.dy);
			if (additional == 0) {
				continue;
			}
			additional = clampWorldHeight(pieces, component, additional, minY, maxY);
			if (maxShift > 0) {
				additional = Math.max(-maxShift, Math.min(maxShift, additional));
			}
			int chosen = resolveOverlap(pieces, component, additional);
			if (chosen != additional) {
				VpsPipeline.OVERLAP_GUARDS.incrementAndGet();
			}
			if (chosen == 0) {
				continue;
			}
			component.dy += chosen;
			for (int idx : component.pieceIndices) {
				pieces.get(idx).move(0, chosen, 0);
			}
			VpsPipeline.SECOND_ALIGN_MOVED.incrementAndGet();
		}
		// only the ADDITIONAL move is propagated; the first pass already synced
		// its own dy (the old code would have moved the junction twice).
		syncJunctions(pieces, components);

		solution.shiftedComponents = shiftedComponents(components);
		// read after both shifts: the boxes must describe where the buildings
		// really are, because the writer uses them to refuse a road column (plan A).
		// Decor is excluded from plan A; its actual blocks are protected block by
		// block (audit 2026-10-20 decision 1).
		StreetDecor.Resolved decorFinal = StreetDecor.refresh(settledDecor, pieces, templateManager);
		solution.buildingBoxes = BuildingComponents.protectedBoxes(pieces, decorFinal.pieceIndices);
		solution.decorBlocks = decorFinal.blocks;
		java.util.Map<Long, String> finalBase = new java.util.HashMap<>(fixtures.fixtureBaseBlocks);
		finalBase.putAll(decorFinal.baseBlocks);
		solution.decorBaseBlocks = finalBase;
		recordDecorSyncMismatch(decorFinal, finalNetwork);
		// Audit 2026-10-21 / user decision: connection is the 4-adjacency between
		// solve cells; count adjacent residuals and pieces with no external edge.
		StreetDecor.recordIsolatedSeats(finalNetwork);
		StreetDecor.recordRoadMismatch(decorFinal, finalNetwork);
		solution.doorFronts = buildDoorFronts(components, pieces, finalTargets, finalNetwork);
		solution.doorSigns = computeDoorSigns(components, finalTargets, finalNetwork, config);
		solution.seamSigns = computeSeamSigns(finalNetwork, config);
		if (reSolved) {
			VpsPipeline.DOOR_TARGET_CHANGED_BY_RESOLVE.addAndGet(countDoorTargetChanges(firstTargets, finalTargets));
		}

		if (config.diagnostics.dumpRoadNetwork) {
			VillagePathSmoothed.LOGGER.info("[VPS] T1.5 village at {}: {} cells, {} building components, {} shifted, {} cells after the post-shift re-solve (second align moved {})",
					chunkPos, network.cells.size(), components.size(), shifted.size(), finalNetwork.cells.size(),
					VpsPipeline.SECOND_ALIGN_MOVED.get());
		}
		return solution;
	}

	private static int[][] shiftedComponents(List<BuildingComponents.Component> components) {
		List<int[]> out = new ArrayList<>();
		for (BuildingComponents.Component component : components) {
			if (component.dy == 0) {
				continue;
			}
			out.add(new int[]{component.minX, component.maxX, component.minZ, component.maxZ,
					component.baseWalkY + component.dy});
		}
		return out.toArray(new int[0][]);
	}

	/**
	 * Builds the main-door targets of every component against one network. The
	 * eligibility filter is the SAME three-way exclusion the writer and the
	 * alignment share (door/basis fix plan 2026-10-17, D3/D4): no seat, no 2D
	 * building footprint, no 3D box the writer would skip. Counters are only
	 * accumulated when {@code countStats} is true (the final pass), so the first
	 * pass does not double them.
	 */
	private static List<List<DoorSigns.DoorTarget>> collectDoorTargets(
			List<BuildingComponents.Component> components, List<StructurePiece> pieces,
			BuildingComponents.TemplateScan scan, RoadNetwork network, int[][] exclusionBoxes, boolean countStats) {
		List<List<DoorSigns.DoorTarget>> out = new ArrayList<>(components.size());
		for (BuildingComponents.Component component : components) {
			out.add(doorTargets(component, pieces, scan, network, exclusionBoxes, countStats));
		}
		return out;
	}

	/** Package-visible bridge for the T5 path: building boxes + decor footprint columns. */
	static int[][] mergeBoxesForT5(int[][] buildingBoxes, int[][] decorColumns) {
		return mergeBoxes(buildingBoxes, decorColumns);
	}

	/** Building boxes + the decor's actual footprint columns, for door-target exclusion only. */
	private static int[][] mergeBoxes(int[][] buildings, int[][] decorColumns) {
		if (decorColumns == null || decorColumns.length == 0) {
			return buildings;
		}
		int[][] out = new int[buildings.length + decorColumns.length][];
		System.arraycopy(buildings, 0, out, 0, buildings.length);
		System.arraycopy(decorColumns, 0, out, buildings.length, decorColumns.length);
		return out;
	}

	private static boolean hasMultiBlockDecor(StreetDecor.Resolved decor) {
		for (StreetDecor.Piece piece : decor.pieces) {
			if (piece.multiBlock) {
				return true;
			}
		}
		return false;
	}

	/** True when every piece of the component is an identified multi-block decor piece. */
	private static boolean isMultiBlockDecorComponent(BuildingComponents.Component component,
			StreetDecor.Resolved decor) {
		boolean hasMulti = false;
		for (int index : component.pieceIndices) {
			boolean multi = false;
			for (StreetDecor.Piece piece : decor.pieces) {
				if (piece.pieceIndex == index) {
					multi = piece.multiBlock;
					break;
				}
			}
			if (!multi) {
				return false;
			}
			hasMulti = true;
		}
		return hasMulti;
	}

	/**
	 * T1.5 seat sync check (audit 2026-10-20 decision 2): after the second
	 * alignment the decor is frozen. Its seat/footprint cells were pinned to the
	 * pre-alignment base and the alignment should have been a no-op there; a cell
	 * that still disagrees is counted, never patched after the road is frozen.
	 */
	private static void recordDecorSyncMismatch(StreetDecor.Resolved decor, RoadNetwork network) {
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (StreetDecor.Piece piece : decor.pieces) {
			if (!piece.multiBlock) {
				continue;
			}
			int expected = 2 * piece.baseWalkY;
			seen.clear();
			seen.addAll(piece.seatColumns);
			seen.addAll(piece.columns);
			for (long column : seen) {
				countMismatch(network, column, expected);
			}
		}
	}

	private static void countMismatch(RoadNetwork network, long column, int expected) {
		int x = (int) (column >> 32);
		int z = (int) column;
		int index = network.cellAt(x, z);
		if (index < 0) {
			return;
		}
		RoadCell cell = network.cells.get(index);
		if (cell.plaza) {
			return;
		}
		if (cell.solvedP != expected) {
			VpsPipeline.DECOR_SEAT_SYNC_MISMATCH.incrementAndGet();
		}
	}

	/** Package-visible bridge for the T5 path (no building move, same door rules). */
	static List<DoorSigns.DoorTarget> doorTargetsFor(BuildingComponents.Component component,
			List<StructurePiece> pieces, BuildingComponents.TemplateScan scan, RoadNetwork network,
			int[][] buildingBoxes, boolean countStats) {
		return doorTargets(component, pieces, scan, network, buildingBoxes, countStats);
	}

	/** Package-visible bridge for the T5 path. */
	static int[][] doorFrontsFor(List<BuildingComponents.Component> components, List<StructurePiece> pieces,
			List<List<DoorSigns.DoorTarget>> targetsPerComponent, RoadNetwork net) {
		return buildDoorFronts(components, pieces, targetsPerComponent, net);
	}

	private static List<DoorSigns.DoorTarget> doorTargets(BuildingComponents.Component component,
			List<StructurePiece> pieces, BuildingComponents.TemplateScan scan, RoadNetwork network,
			int[][] buildingBoxes, boolean countStats) {
		List<int[]> doors = new ArrayList<>();
		List<int[]> entrances = new ArrayList<>();
		for (int idx : component.pieceIndices) {
			int[][] pieceDoors = scan.doors.get(idx);
			if (pieceDoors != null) {
				for (int[] door : pieceDoors) {
					doors.add(door);
				}
			}
			int[][] pieceEntrances = scan.entrances.get(idx);
			if (pieceEntrances != null) {
				for (int[] entrance : pieceEntrances) {
					entrances.add(entrance);
				}
			}
		}
		if (doors.isEmpty()) {
			return List.of(); // no real door: junction fallback (farm/pen)
		}
		int[][] doorArray = doors.toArray(new int[0][]);
		int[][] entrancePositions = new int[entrances.size()][];
		for (int i = 0; i < entrances.size(); i++) {
			entrancePositions[i] = new int[]{entrances.get(i)[0], entrances.get(i)[1]};
		}
		int[] main = DoorSigns.mainDoorIndices(doorArray, entrancePositions);
		if (countStats) {
			if (entrancePositions.length == 0) {
				VpsPipeline.MAIN_DOOR_NO_ENTRANCE.incrementAndGet();
			}
			VpsPipeline.MAIN_DOOR_SELECTED.addAndGet(main.length);
		}
		java.util.function.IntPredicate eligible = index -> {
			RoadCell cell = network.cells.get(index);
			if (cell.seat) {
				if (countStats) {
					VpsPipeline.SEAT_TARGET_EXCLUDED.incrementAndGet();
				}
				return false;
			}
			if (RoadWritePolicy.insideFootprint(buildingBoxes, cell.x, cell.z)
					|| RoadWritePolicy.insideBuilding(buildingBoxes, cell.x, cell.z, cell.solvedP)) {
				if (countStats) {
					VpsPipeline.DOOR_TARGET_INSIDE_BUILDING.incrementAndGet();
				}
				return false;
			}
			return true;
		};
		List<DoorSigns.DoorTarget> out = new ArrayList<>(main.length);
		for (int index : main) {
			int[] door = doorArray[index];
			int[] normal = doorNormal(component, door[0], door[1], entrances);
			DoorSigns.Selection selection = DoorSigns.selectDoorRoadCell(network, door[0], door[1],
					normal[0], normal[1], eligible);
			if (countStats) {
				VpsPipeline.DOOR_TARGET_BEHIND.addAndGet(selection.behindExcluded());
			}
			RoadCell road = selection.cell();
			int[] outStep = road != null ? DoorSigns.stepToward(road, door[0], door[1]) : null;
			if (outStep == null) {
				outStep = normal;
			}
			if (road == null && countStats) {
				VpsPipeline.NO_FRONT_ROAD.incrementAndGet();
			}
			int floorY = door.length > 2 ? door[2] : component.baseWalkY;
			out.add(new DoorSigns.DoorTarget(door[0], door[1], floorY,
					normal[0], normal[1], outStep[0], outStep[1], road));
		}
		return out;
	}

	/**
	 * The outward normal of the wall a main door sits in (door/basis fix plan
	 * 2026-10-17, section 3.4): the nearest of the component's four footprint
	 * faces. A corner tie is resolved by the {@code building_entrance} front,
	 * then by the axis on which the entrance lies, then by +x; template door
	 * leaves are deliberately not used (only ~30% face outward).
	 */
	private static int[] doorNormal(BuildingComponents.Component component, int doorX, int doorZ,
			List<int[]> entrances) {
		int dxMin = doorX - component.minX;
		int dxMax = component.maxX - doorX;
		int dzMin = doorZ - component.minZ;
		int dzMax = component.maxZ - doorZ;
		int min = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));
		List<int[]> tied = new ArrayList<>(4);
		if (dxMin == min) {
			tied.add(new int[]{-1, 0, dxMin});
		}
		if (dxMax == min) {
			tied.add(new int[]{1, 0, dxMax});
		}
		if (dzMin == min) {
			tied.add(new int[]{0, -1, dzMin});
		}
		if (dzMax == min) {
			tied.add(new int[]{0, 1, dzMax});
		}
		if (tied.size() == 1) {
			return new int[]{tied.get(0)[0], tied.get(0)[1]};
		}
		int[] entrance = nearestEntrance(doorX, doorZ, entrances);
		if (entrance != null) {
			for (int[] candidate : tied) {
				if (candidate[0] == entrance[2] && candidate[1] == entrance[3]) {
					return new int[]{candidate[0], candidate[1]};
				}
			}
			if (Math.abs(entrance[0] - doorX) >= Math.abs(entrance[1] - doorZ)) {
				if (dxMin == min) {
					return new int[]{-1, 0};
				}
				if (dxMax == min) {
					return new int[]{1, 0};
				}
			} else {
				if (dzMin == min) {
					return new int[]{0, -1};
				}
				if (dzMax == min) {
					return new int[]{0, 1};
				}
			}
		}
		VpsPipeline.DOOR_NORMAL_AMBIGUOUS.incrementAndGet();
		if (dxMin == min) {
			return new int[]{-1, 0};
		}
		if (dxMax == min) {
			return new int[]{1, 0};
		}
		if (dzMin == min) {
			return new int[]{0, -1};
		}
		return new int[]{0, 1};
	}

	private static int[] nearestEntrance(int doorX, int doorZ, List<int[]> entrances) {
		int[] best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int[] entrance : entrances) {
			int distance = Math.max(Math.abs(entrance[0] - doorX), Math.abs(entrance[1] - doorZ));
			if (distance < bestDistance) {
				best = entrance;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Door fronts for the railing exemption: one cell one step outside each main
	 * door, from the same {@link DoorSigns.DoorTarget} the alignment and the sign
	 * use. A component without real doors keeps the junction fallback.
	 */
	private static int[][] buildDoorFronts(List<BuildingComponents.Component> components,
			List<StructurePiece> pieces, List<List<DoorSigns.DoorTarget>> targetsPerComponent, RoadNetwork net) {
		List<int[]> out = new ArrayList<>();
		for (int c = 0; c < components.size(); c++) {
			List<DoorSigns.DoorTarget> targets = targetsPerComponent.get(c);
			if (!targets.isEmpty()) {
				for (DoorSigns.DoorTarget target : targets) {
					if (target.road() == null) {
						VpsPipeline.DOOR_FRONT_FALLBACK.incrementAndGet();
					}
					out.add(new int[]{target.doorX() + target.outX(), target.doorZ() + target.outZ(),
							target.outX(), target.outZ()});
				}
				continue;
			}
			BuildingComponents.Component component = components.get(c);
			int cx = (component.minX + component.maxX) / 2;
			int cz = (component.minZ + component.maxZ) / 2;
			for (int idx : component.pieceIndices) {
				StructurePiece piece = pieces.get(idx);
				if (!(piece instanceof PoolElementStructurePiece pool)) {
					continue;
				}
				for (JigsawJunction junction : pool.getJunctions()) {
					if (junction.getDestProjection() == StructureTemplatePool.Projection.RIGID) {
						continue;
					}
					addJunctionFront(out, junction.getSourceX(), junction.getSourceZ(), cx, cz, net);
				}
			}
		}
		return out.toArray(new int[0][]);
	}

	/** One junction front, the pre-F-C fallback for farms/pens without real doors. */
	private static void addJunctionFront(List<int[]> out, int ax, int az, int cx, int cz, RoadNetwork net) {
		int[] outward = BuildingComponents.outwardAxis(ax, az, cx, cz);
		int[] towardRoad = net != null ? DoorSigns.stepTowardRoad(net, ax, az) : null;
		if (towardRoad != null && outward != null
				&& (towardRoad[0] != outward[0] || towardRoad[1] != outward[1])) {
			VpsPipeline.DOOR_FRONT_DIR_MISMATCH.incrementAndGet();
		}
		int[] axis = towardRoad != null ? towardRoad : outward;
		if (axis == null) {
			return;
		}
		out.add(new int[]{ax + axis[0], az + axis[1], axis[0], axis[1]});
	}

	/** Door target changes between the first and the final network (XZ or solvedP). */
	private static int countDoorTargetChanges(List<List<DoorSigns.DoorTarget>> before,
			List<List<DoorSigns.DoorTarget>> after) {
		int changed = 0;
		for (int c = 0; c < Math.min(before.size(), after.size()); c++) {
			List<DoorSigns.DoorTarget> a = before.get(c);
			List<DoorSigns.DoorTarget> b = after.get(c);
			for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
				RoadCell ra = a.get(i).road();
				RoadCell rb = b.get(i).road();
				if (ra == null || rb == null) {
					if (ra != rb) {
						changed++;
					}
				} else if (ra.x != rb.x || ra.z != rb.z || ra.solvedP != rb.solvedP) {
					changed++;
				}
			}
		}
		return changed;
	}

	/**
	 * Debug door signs (user request 2026-10-16, opt-in since 2026-10-17): one standing sign
	 * per MAIN door - the template's real door nearest {@code building_entrance}
	 * (door/basis fix plan 2026-10-17, D2). Line 1 is the door X/Z with the
	 * component's {@code baseWalkY + dy}; line 2 the selected road cell and its
	 * solved walk surface; line 3 the actual door floor after the shift; line 4
	 * {@code NO FRONT} when no eligible outward cell existed. Pure data; the
	 * writer places the blocks. Must run on the FINAL network so line 2 prints
	 * the surface the writer will actually render.
	 */
	static int[][] computeDoorSigns(List<BuildingComponents.Component> components,
			List<List<DoorSigns.DoorTarget>> targetsPerComponent, RoadNetwork net,
			VillagePathSmoothedConfig config) {
		if (!config.debug.doorSigns) {
			return new int[0][];
		}
		int[] baseWalkY = new int[components.size()];
		for (int c = 0; c < components.size(); c++) {
			BuildingComponents.Component component = components.get(c);
			baseWalkY[c] = component.baseWalkY + component.dy;
		}
		int[][] signs = DoorSigns.compute(targetsPerComponent, baseWalkY, net);
		int doorCount = 0;
		for (List<DoorSigns.DoorTarget> targets : targetsPerComponent) {
			doorCount += targets.size();
		}
		VpsPipeline.DOOR_SIGNS_WITHOUT_ROAD.addAndGet(doorCount - signs.length);
		return signs;
	}

	/**
	 * Debug seam signs (user request 2026-10-16, opt-in since 2026-10-17): one
	 * sign per seam cell of the solved mask, so the half-block guarantee can be
	 * verified on foot. The illegal edges (|dP| more than half a block) are
	 * counted as observations; the sign itself names the cause on lines 3/4.
	 */
	static int[][] computeSeamSigns(RoadNetwork net, VillagePathSmoothedConfig config) {
		if (!config.debug.seamSigns) {
			return new int[0][];
		}
		int[][] signs = SeamSigns.compute(net, config.roadSmoothing.allowCutIntoTerrain);
		VpsPipeline.SEAM_SIGNS_COMPUTED.addAndGet(signs.length);
		VpsPipeline.SEAM_STEP_VIOLATIONS.addAndGet(SeamSigns.violationCount(signs));
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
			component.baseWalkY = baseY;
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
	 * Road heights of a component's main-door targets. Only targets with an
	 * eligible outward cell count; {@code null} means no front at all, and the
	 * caller must not move the component for it (door/basis fix plan 2026-10-17,
	 * D4).
	 */
	private static int[] adjacentRoadHeights(List<DoorSigns.DoorTarget> targets) {
		int count = 0;
		for (DoorSigns.DoorTarget target : targets) {
			if (target.road() != null) {
				count++;
			}
		}
		if (count == 0) {
			return null;
		}
		int[] out = new int[count];
		int n = 0;
		for (DoorSigns.DoorTarget target : targets) {
			if (target.road() != null) {
				out[n++] = target.road().solvedP;
			}
		}
		return out;
	}

	/** The windowed junction fallback for a component without real doors (farm/pen). */
	private static int desiredShiftFromJunctions(RoadNetwork network, BuildingComponents.Component component,
			List<StructurePiece> pieces, VillagePathSmoothedConfig config) {
		int[] window = {
				component.minX - DOOR_SEARCH_PAD, component.maxX + DOOR_SEARCH_PAD,
				component.minZ - DOOR_SEARCH_PAD, component.maxZ + DOOR_SEARCH_PAD
		};
		List<Integer> values = new ArrayList<>();
		boolean hasJunction = false;
		for (int idx : component.pieceIndices) {
			StructurePiece piece = pieces.get(idx);
			if (!(piece instanceof PoolElementStructurePiece pool)) {
				continue;
			}
			for (JigsawJunction junction : pool.getJunctions()) {
				if (junction.getDestProjection() == StructureTemplatePool.Projection.RIGID) {
					continue;
				}
				hasJunction = true;
				RoadCell road = DoorSigns.nearestRoadCell(network, junction.getSourceX(), junction.getSourceZ(), window);
				if (road != null) {
					values.add(road.solvedP);
				}
			}
		}
		if (!hasJunction) {
			int cx = (component.minX + component.maxX) / 2;
			int cz = (component.minZ + component.maxZ) / 2;
			RoadCell road = DoorSigns.nearestRoadCell(network, cx, cz, window);
			if (road != null) {
				values.add(road.solvedP);
			}
		}
		return decisionFromHeights(values, component, config);
	}

	/** How far past the footprint a junction's fallback road cell may sit and still count. */
	private static final int DOOR_SEARCH_PAD = 4;

	/** The authoritative shift from the selected main-door road cells. */
	private static int desiredShiftFromTargets(List<DoorSigns.DoorTarget> targets,
			BuildingComponents.Component component, VillagePathSmoothedConfig config) {
		int[] adjacent = adjacentRoadHeights(targets);
		if (adjacent == null) {
			return BuildingShift.DO_NOT_MOVE;
		}
		BuildingShift.ShiftDecision decision = BuildingShift.desiredShift(adjacent, component.baseWalkY);
		return applyDecision(decision, config);
	}

	private static int decisionFromHeights(List<Integer> values, BuildingComponents.Component component,
			VillagePathSmoothedConfig config) {
		if (values.isEmpty()) {
			return BuildingShift.DO_NOT_MOVE;
		}
		int[] adjacent = new int[values.size()];
		for (int i = 0; i < adjacent.length; i++) {
			adjacent[i] = values.get(i);
		}
		return applyDecision(BuildingShift.desiredShift(adjacent, component.baseWalkY), config);
	}

	/**
	 * Vertical shift so the component's base walk level meets the solid levels of
	 * the selected road cells, delegated to the pure {@link BuildingShift}
	 * helper with the diagnostic counters kept here. The optional median
	 * two-wing limit ({@code buildingShiftMedianWingBlocks}, 0 = off) clamps the
	 * chosen shift around the legacy median scheme's shift.
	 */
	private static int applyDecision(BuildingShift.ShiftDecision decision, VillagePathSmoothedConfig config) {
		if (decision.reason() == BuildingShift.Reason.NO_ROAD) {
			return BuildingShift.DO_NOT_MOVE;
		}
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
			// second alignment (D5, 2026-10-17): propagate only the ADDITIONAL
			// move; the first pass already synced its own dy
			int delta = component.dy - component.syncedDy;
			if (delta == 0) {
				continue;
			}
			for (int idx : component.pieceIndices) {
				StructurePiece piece = pieces.get(idx);
				if (piece instanceof PoolElementStructurePiece pool) {
					for (JigsawJunction junction : pool.getJunctions()) {
						dyByIdentity.put(junction, delta);
					}
				}
			}
		}
		if (dyByIdentity.isEmpty()) {
			for (BuildingComponents.Component component : components) {
				component.syncedDy = component.dy;
			}
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
		for (BuildingComponents.Component component : components) {
			component.syncedDy = component.dy;
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
					cell.edgeClamped = true;
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
			BuildingComponents.TemplateScan scan) {
		for (BuildingComponents.Component component : components) {
			List<int[]> doors = new ArrayList<>();
			for (int idx : component.pieceIndices) {
				int[][] pieceDoors = scan.doors.get(idx);
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

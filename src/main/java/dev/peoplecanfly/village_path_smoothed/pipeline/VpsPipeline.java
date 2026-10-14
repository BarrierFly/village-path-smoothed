/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.ProbeCaliber;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.RoadShapeStage;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.StructureStartVpsAccess;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point of the placement chain. Split in two by review 4 section 9:
 * {@link #onStructurePlacementHead} runs before vanilla places anything and
 * opens the suppression context, {@link #onStructurePlacedInChunk} runs after
 * and writes the road.
 *
 * <p>The village is solved once and cached on the start instance, so the two
 * hooks never solve it twice.
 */
public final class VpsPipeline {
	public static final AtomicLong VILLAGES_SOLVED = new AtomicLong();
	public static final AtomicLong SOLVE_NANOS = new AtomicLong();
	public static final AtomicLong ROAD_CELLS_WRITTEN = new AtomicLong();
	public static final AtomicLong KERBS_PLACED = new AtomicLong();
	public static final AtomicLong RAILINGS_PLACED = new AtomicLong();
	public static final AtomicLong SCAFFOLDS_PLACED = new AtomicLong();
	public static final AtomicLong SCAFFOLD_WATER_SELF = new AtomicLong();
	public static final AtomicLong SCAFFOLD_WATER_BELOW = new AtomicLong();
	public static final AtomicLong SCAFFOLD_DRY = new AtomicLong();
	// Review 4 risk R3: a claimed cell whose profile write threw and fell back
	// to a surface-anchored road block. Should stay at zero; a non-zero value
	// means the profile write is throwing per cell.
	public static final AtomicLong SURFACE_FALLBACK_WRITES = new AtomicLong();
	public static final AtomicLong T15_APPLIED = new AtomicLong();
	public static final AtomicLong T15_NO_SERVER = new AtomicLong();
	public static final AtomicLong BUILDING_COMPONENTS = new AtomicLong();
	public static final AtomicLong BUILDINGS_SHIFTED = new AtomicLong();
	public static final AtomicLong BUILDINGS_NO_ROAD_ACCESS = new AtomicLong();
	public static final AtomicLong OVERLAP_GUARDS = new AtomicLong();
	public static final AtomicLong DOOR_PARITY_FALLBACK = new AtomicLong();
	// Review 3 P0-A: the solver's residual diagnostics. Everything here used to
	// be silent: contradictory candidate bands quietly kept the first-pass value
	// and leftover |dP|>1 edges were never looked at again. The dead
	// SOLVE_NON_CONVERGED counter (declared, never incremented) is replaced by
	// these.
	public static final AtomicLong SOLVE_CANDIDATE_CONTRADICTIONS = new AtomicLong();
	public static final AtomicLong SOLVE_STEP_VIOLATIONS = new AtomicLong();
	public static final AtomicLong SOLVE_WORST_STEP = new AtomicLong();
	/** Fix plan v4 A2: arms whose phase-3 seam re-solve was refused for state budget. */
	public static final AtomicLong SOLVE_PHASE3_REFUSALS = new AtomicLong();
	// Fix plan v4 A-1 / decision N3-a: what the polish pass had to do to make the
	// profile legal. ROAD_POLISH_MAX_DELTA is the visible price of "no drop larger
	// than half a block": a single cell can be dragged several whole blocks.
	public static final AtomicLong ROAD_POLISH_MOVED = new AtomicLong();
	public static final AtomicLong ROAD_POLISH_MAX_DELTA = new AtomicLong();
	public static final AtomicLong ROAD_POLISH_DELTA_P90 = new AtomicLong();
	public static final AtomicLong ROAD_POLISH_CONFLICTS = new AtomicLong();
	// Fix plan v2 R4.3: water-crossing cells whose solved surface deviates from
	// the water surface (their preferred height). The anchor is soft, so this
	// counts how often the solver traded the water level for smoothness.
	public static final AtomicLong WATER_ANCHOR_DEVIATIONS = new AtomicLong();
	// Review 4 section 9: vanilla street blocks dropped from the placement chain
	// on claimed cells. Reconciliation rule: suppressed ~= road cells written
	// minus the anchor cells (anchors keep their vanilla block by decision),
	// split per chunk, so a mismatch means the mask and the suppression set
	// disagree (risk R2) and both show up at once in /vps stats.
	public static final AtomicLong SUPPRESSED_VANILLA_ROAD_BLOCKS = new AtomicLong();
	public static final AtomicLong SUPPRESSION_PASSED_THROUGH = new AtomicLong();
	// Review 3 section 1: a village whose street widths all exceed
	// maxExactSectionWidth decomposes into ONE giant region - arms = 0 - and the
	// exact arm DP plus the seam phase never run for it. Worth watching.
	public static final AtomicLong VILLAGES_WITHOUT_ARMS = new AtomicLong();
	public static final AtomicLong BUILDING_FLOATING = new AtomicLong();
	public static final AtomicLong BUILDING_BURIED = new AtomicLong();
	public static final AtomicLong DEBUG_VILLAGER_BRAIN_SKIPS = new AtomicLong();

	private VpsPipeline() {
	}

	public static void onStructurePlacementHead(
			StructureStart start,
			WorldGenLevel level,
			ChunkGenerator generator,
			ChunkPos chunkPos
	) {
		VpsVillageSolution solution = prepare(start, level, generator, chunkPos);
		if (solution != null) {
			VpsPlacementContext.push(solution.network);
		}
	}

	public static void onStructurePlacedInChunk(
			StructureStart start,
			WorldGenLevel level,
			ChunkGenerator generator,
			BoundingBox chunkBB,
			ChunkPos chunkPos
	) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		StructureStartVpsAccess access = (StructureStartVpsAccess) (Object) start;
		VpsVillageSolution solution = access.village_path_smoothed$getSolution();
		// The context is popped first: a failure further down must not leave a
		// stale mask live on a reused world-gen thread. The caller already wraps
		// this hook in its own try/catch, so there is nothing to add here.
		VpsPlacementContext.pop();
		if (solution == null || solution.network.cells.isEmpty()) {
			return;
		}
		VpsGenContext ctx = VpsGenContext.capture(level, generator, chunkPos);
		if (solution.t15Applied && config.roadSmoothing.buildingGuard && !solution.guardChecked) {
			solution.guardChecked = true;
			runBuildingGuard(solution, ctx, config);
		}
		RoadWriter.write(solution.network, ctx, chunkBB, config, solution.shiftedComponents,
				solution.buildingBoxes, solution.doorFronts, String.valueOf(start.getChunkPos()));
	}

	/**
	 * Solves the village (cached on the start) and returns the solution when
	 * the suppression context must be opened: the world is enabled, the start
	 * is a village, and the road mask is non-empty. Every other case returns
	 * null, which means a pure pass-through for the placement chain.
	 */
	private static VpsVillageSolution prepare(
			StructureStart start,
			WorldGenLevel level,
			ChunkGenerator generator,
			ChunkPos chunkPos
	) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		if (start == null || !start.isValid()) {
			return null;
		}
		// the per-world switch is authoritative; the global config "enabled" only
		// seeds the default for worlds without their own file (VpsWorldSettings)
		MinecraftServer server = serverOf(level);
		if (server == null || !VpsWorldSettings.isEnabled(server)) {
			return null;
		}
		boolean isVanillaVillage = VpsCompat.isVanillaVillageStructure(level, start.getStructure());
		if (!isVanillaVillage && !RoadCollector.hasCustomRegex(config)) {
			return null; // fast path: other structures cost nothing
		}

		// StructureStart is final: cast through Object so the compiler accepts the mixin duck interface
		StructureStartVpsAccess access = (StructureStartVpsAccess) (Object) start;
		VpsVillageSolution solution = access.village_path_smoothed$getSolution();
		if (solution == null) {
			synchronized (start) {
				solution = access.village_path_smoothed$getSolution();
				if (solution == null) {
					solution = solveVillage(start, level, generator, chunkPos, config);
					access.village_path_smoothed$setSolution(solution);
				}
			}
		}
		return solution.network.cells.isEmpty() ? null : solution;
	}

	/**
	 * T1.5 hook (B1): called at the tail of {@code Structure#generate}, before
	 * the bounding box is cached. Runs the joint forward decision (road profile
	 * + RIGID component shifts) when the stage is T1_5, the world is enabled and
	 * the server could be resolved; attaches the solution to the StructureStart
	 * for the feature-placement write. Fails safe: on any missing precondition
	 * the pieces are left untouched and the legacy T5 solve runs later.
	 */
	public static void onStructureGenerated(
			StructureStart start,
			ChunkGenerator generator,
			RandomState randomState,
			StructureTemplateManager templateManager,
			ChunkPos chunkPos,
			LevelHeightAccessor heightAccessor
	) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		if (start == null || !start.isValid()) {
			return;
		}
		if (config.roadSmoothing.roadShapeStage != RoadShapeStage.T1_5 || !config.roadSmoothing.enabled) {
			return;
		}
		MinecraftServer server = VpsGenThread.server();
		if (server == null) {
			// R-B19: never "default allow" - leave the pieces alone and let the
			// FEATURES hook fall back to the T5 solve (no building move)
			T15_NO_SERVER.incrementAndGet();
			return;
		}
		if (!VpsWorldSettings.isEnabled(server)) {
			return;
		}
		if (!hasStreetPiece(start, config)) {
			return;
		}
		StructureStartVpsAccess access = (StructureStartVpsAccess) (Object) start;
		if (access.village_path_smoothed$getSolution() != null) {
			return; // idempotent (F10)
		}
		VpsVillageSolution solution = T15Planner.plan(start, generator, randomState, templateManager, chunkPos, heightAccessor, config);
		access.village_path_smoothed$setSolution(solution);
		T15_APPLIED.incrementAndGet();
	}

	private static boolean hasStreetPiece(StructureStart start, VillagePathSmoothedConfig config) {
		for (StructurePiece piece : start.getPieces()) {
			if (RoadCollector.isStreetPiece(piece, config)) {
				return true;
			}
		}
		return false;
	}

	/** B4: pure diagnostic - count moved components that ended up floating or buried; never repairs. */
	private static void runBuildingGuard(VpsVillageSolution solution, VpsGenContext ctx, VillagePathSmoothedConfig config) {
		for (int[] component : solution.shiftedComponents) {
			int cx = (component[0] + component[1]) / 2;
			int cz = (component[2] + component[3]) / 2;
			int baseY = component[4];
			int realTop;
			try {
				realTop = ctx.surfaceTopY(cx, cz, config);
			} catch (Throwable t) {
				continue;
			}
			int diff = realTop - baseY;
			if (diff > 3) {
				BUILDING_BURIED.incrementAndGet();
			} else if (diff < -3) {
				BUILDING_FLOATING.incrementAndGet();
			}
		}
	}

	private static volatile boolean warnedServerLookup;

	private static MinecraftServer serverOf(WorldGenLevel level) {
		try {
			if (level instanceof net.minecraft.server.level.WorldGenRegion region) {
				return region.getLevel().getServer();
			}
			return level.getServer();
		} catch (Throwable t) {
			if (!warnedServerLookup) {
				warnedServerLookup = true;
				VillagePathSmoothed.LOGGER.warn("[VPS] could not resolve the owning server; the per-world switch is unavailable and roads stay vanilla", t);
			}
			return null;
		}
	}

	private static VpsVillageSolution solveVillage(StructureStart start, WorldGenLevel level, ChunkGenerator generator, ChunkPos triggerChunk, VillagePathSmoothedConfig config) {
		long begin = System.nanoTime();
		// Fix plan v4 D2-c / R2-1: the real-heightmap window is anchored on the
		// village's bounding box, so the solve - and therefore the road - does not
		// depend on which chunk's placeInChunk triggered it. The old anchor was the
		// triggering chunk, which made the same seed produce two different villages
		// depending on generation order: the columns inside the window carry carver
		// and beard, so they are not a pure function of x/z. The +/-1 chunk size is
		// vanilla's blockStateWriteRadius for FEATURES, not a limit of the solve -
		// getBaseHeight itself is a pure function of arbitrary x/z.
		//
		// Note this is a write-stage property: the T1.5 solve runs during
		// STRUCTURE_STARTS, where the village has no terrain at all yet (F14/F16).
		boolean realCaliber = config.roadSmoothing.probeCaliber != ProbeCaliber.ALL_NOISE;
		VpsGenContext ctx = VpsGenContext.capture(level, generator, triggerChunk, realCaliber);
		if (realCaliber) {
			ctx.anchorOnVillage(start.getBoundingBox());
		}
		RoadNetwork network = RoadCollector.collect(start, ctx, config);
		if (config.roadSmoothing.enabled) {
			RoadSmoother.SolveStats stats = RoadSmoother.solve(network, paramsFor(config));
			accumulateSolveStats(stats);
		}
		if (!network.cells.isEmpty() && network.arms.isEmpty()) {
			VILLAGES_WITHOUT_ARMS.incrementAndGet();
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {}: {} road cells but 0 arms - every street is wider than maxExactSectionWidth ({}), the whole village degrades into {} giant region(s) and the exact arm DP never runs",
					start.getChunkPos(), network.cells.size(), config.roadSmoothing.maxExactSectionWidth, network.regions.size());
		}
		VpsVillageSolution solution = new VpsVillageSolution(network);
		solution.solved = true;
		// T5 never moves buildings, so the boxes are already final here
		solution.buildingBoxes = BuildingComponents.movableBoxes(start.getPieces());
		solution.doorFronts = BuildingComponents.doorFronts(start.getPieces());

		long nanos = System.nanoTime() - begin;
		SOLVE_NANOS.addAndGet(nanos);
		VILLAGES_SOLVED.incrementAndGet();
		if (config.diagnostics.dumpRoadNetwork) {
			// baseP min/avg make a garbage baseline visible at a glance (report 5-④)
			long baseSum = 0;
			int baseMin = Integer.MAX_VALUE;
			for (RoadCell cell : network.cells) {
				baseSum += cell.baseP;
				baseMin = Math.min(baseMin, cell.baseP);
			}
			int baseAvg = network.cells.isEmpty() ? 0 : (int) (baseSum / network.cells.size());
			VillagePathSmoothed.LOGGER.info("[VPS] solved village at {} in {} us: {} cells, {} arms, {} regions, baseP min/avg {}/{}",
					start.getChunkPos(), nanos / 1000, network.cells.size(), network.arms.size(), network.regions.size(), baseMin, baseAvg);
		}
		return solution;
	}

	/** One shared accumulation point for the T5 and T1.5 solve paths (fix plan v2, R1 precondition). */
	public static void accumulateSolveStats(RoadSmoother.SolveStats stats) {
		SOLVE_CANDIDATE_CONTRADICTIONS.addAndGet(stats.candidateContradictions());
		SOLVE_STEP_VIOLATIONS.addAndGet(stats.residualViolations());
		SOLVE_WORST_STEP.accumulateAndGet(stats.worstStep(), Math::max);
		SOLVE_PHASE3_REFUSALS.addAndGet(stats.phase3Refusals());
		ROAD_POLISH_MOVED.addAndGet(stats.polishMoved());
		ROAD_POLISH_MAX_DELTA.accumulateAndGet(stats.polishMaxDelta(), Math::max);
		ROAD_POLISH_DELTA_P90.accumulateAndGet(stats.polishDeltaP90(), Math::max);
		ROAD_POLISH_CONFLICTS.addAndGet(stats.polishConflicts());
		WATER_ANCHOR_DEVIATIONS.addAndGet(stats.waterAnchorDeviations());
	}

	public static RoadSmoother.SolveParams paramsFor(VillagePathSmoothedConfig config) {
		var smoothing = config.roadSmoothing;
		int hybridWeight = (int) Math.round(Math.max(0.0, smoothing.hybridWeightDeviation) * 16);
		int window = Math.max(1, Math.min(smoothing.dpWindowHalfUnits, 64));
		return new RoadSmoother.SolveParams(
				smoothing.objective,
				hybridWeight,
				Math.max(0, smoothing.stepViolationPenalty),
				window,
				Math.max(0, Math.min(smoothing.icmRounds, 64)),
				smoothing.allowCutIntoTerrain
		);
	}
}

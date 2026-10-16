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
import dev.peoplecanfly.village_path_smoothed.duck.PoolElementStructurePieceVpsAccess;
import dev.peoplecanfly.village_path_smoothed.duck.StructureStartVpsAccess;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
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
	/**
	 * Villages re-solved by a SECOND StructureStart instance of an already-solved
	 * village (fix plan analysis 2026-10-15 section 3.5, F10). The T1.5 solution
	 * is cached on the start instance that ran {@code Structure#generate}, but a
	 * village spans many chunks and not every chunk's placement sees that same
	 * instance - the extra instances used to fall through to a full T5 re-solve
	 * (no building move!) and the writer then consumed a network that did not
	 * match the shifted pieces. The anchor-keyed cache below closes that.
	 */
	public static final AtomicLong SOLUTION_CACHE_HITS = new AtomicLong();
	public static final AtomicLong SOLVE_NANOS = new AtomicLong();
	public static final AtomicLong ROAD_CELLS_WRITTEN = new AtomicLong();
	public static final AtomicLong KERBS_PLACED = new AtomicLong();
	public static final AtomicLong RAILINGS_PLACED = new AtomicLong();
	public static final AtomicLong SCAFFOLDS_PLACED = new AtomicLong();
	public static final AtomicLong SCAFFOLD_WATER_SELF = new AtomicLong();
	public static final AtomicLong SCAFFOLD_WATER_BELOW = new AtomicLong();
	public static final AtomicLong SCAFFOLD_DRY = new AtomicLong();
	/** User decision 2026-10-15: slabs and leaf railings placed into water go down waterlogged. */
	public static final AtomicLong WATERLOGGED_PLACEMENTS = new AtomicLong();
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
	// F-D (door two-symptom recheck 2026-10-15): the per-junction Chebyshev
	// distance from the connection point to the nearest template door block.
	// The distribution that makes the old anchor mismatch (candidate C of the
	// recheck report) visible - 53 of the 120 vanilla house templates sit at
	// offset 2 or more and used to align on the wrong side.
	public static final AtomicLong DOOR_ANCHOR_OFFSET_0 = new AtomicLong();
	public static final AtomicLong DOOR_ANCHOR_OFFSET_1 = new AtomicLong();
	public static final AtomicLong DOOR_ANCHOR_OFFSET_2 = new AtomicLong();
	public static final AtomicLong DOOR_ANCHOR_OFFSET_3 = new AtomicLong();
	public static final AtomicLong DOOR_ANCHOR_OFFSET_4_PLUS = new AtomicLong();
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
	// Plan C (user decision 2026-10-15): the plaza groups - one rigid flat level
	// each, solved by the solver and rendered by the moved pieces.
	public static final AtomicLong PLAZA_GROUPS = new AtomicLong();
	public static final AtomicLong PLAZAS_SHIFTED = new AtomicLong();
	public static final AtomicLong PLAZA_PIECES_SHIFTED = new AtomicLong();

	private VpsPipeline() {
	}

	/**
	 * Fix plan analysis 2026-10-15 section 3.5 (F10): the solved village is
	 * keyed by (dimension, structure, anchor chunk) instead of only by the
	 * StructureStart instance. Every instance of the same village reports the
	 * same anchor chunk ({@code start.getChunkPos()} is serialised with the
	 * start), so a placement that meets a fresh instance finds the exact network
	 * the T1.5 shift was computed against - "the write consumes the solution the
	 * shift used" now holds across instances, and the duplicate per-instance T5
	 * re-solves (three for the diagnosed village) disappear.
	 *
	 * <p>Bounded LRU: a solution carries a road network (up to the 20k-cell
	 * cap), so 16 entries is a few MB at worst. Cleared by {@code /vps config
	 * reload} because a solution is a function of the config.
	 */
	private static final int SOLUTION_CACHE_LIMIT = 16;
	private static final java.util.LinkedHashMap<CacheKey, VpsVillageSolution> SOLUTION_CACHE =
			new java.util.LinkedHashMap<>(32, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<CacheKey, VpsVillageSolution> eldest) {
					return size() > SOLUTION_CACHE_LIMIT;
				}
			};

	/**
	 * Cache key: dimension id, the Structure INSTANCE (identity is stable within
	 * a server session and needs no registry lookup - the T1.5 site has no
	 * LevelReader) and the anchor chunk, which is serialised with every
	 * StructureStart of the village.
	 */
	private record CacheKey(String dimension, net.minecraft.world.level.levelgen.structure.Structure structure, long anchor) {
	}

	private static CacheKey cacheKey(String dimension, StructureStart start) {
		return new CacheKey(dimension, start.getStructure(), anchorChunkLong(start.getChunkPos()));
	}

	/** Packs the anchor chunk; 26.3 turned ChunkPos's x/z fields into accessors. */
	private static long anchorChunkLong(ChunkPos pos) {
		//#if MC >= 260000
		//$$ return ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
		//#else
		return ((long) pos.x << 32) | (pos.z & 0xFFFFFFFFL);
		//#endif
	}

	private static VpsVillageSolution cachedSolution(String dimension, StructureStart start) {
		synchronized (SOLUTION_CACHE) {
			return SOLUTION_CACHE.get(cacheKey(dimension, start));
		}
	}

	private static void cacheSolution(String dimension, StructureStart start, VpsVillageSolution solution) {
		synchronized (SOLUTION_CACHE) {
			SOLUTION_CACHE.put(cacheKey(dimension, start), solution);
		}
	}

	/** Clears the anchor-keyed solution cache; called when the config (and with it every solution) goes stale. */
	public static void clearSolutionCache() {
		synchronized (SOLUTION_CACHE) {
			SOLUTION_CACHE.clear();
		}
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
		String dimensionKey = dimensionKeyOf(level);
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
					// F10: another instance of this village may have solved it already
					// (the T1.5 pass, or the first chunk that reached the T5 solve).
					solution = dimensionKey != null ? cachedSolution(dimensionKey, start) : null;
					if (solution != null) {
						SOLUTION_CACHE_HITS.incrementAndGet();
					} else {
						solution = solveVillage(start, level, generator, chunkPos, config);
						if (dimensionKey != null) {
							cacheSolution(dimensionKey, start, solution);
						}
					}
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
		// F10: publish the T1.5 solution under the anchor key, so every later
		// StructureStart instance of the same village (deserialized per chunk)
		// consumes the network the shift was computed against. The dimension id
		// comes from the createStructures capture; without it (no server resolved)
		// the instance field alone still serves this instance's own chunks.
		String dimension = VpsGenThread.dimensionKey();
		if (dimension != null) {
			cacheSolution(dimension, start, solution);
		}
		T15_APPLIED.incrementAndGet();
	}

	/**
	 * Dimension id ("namespace:path") of the level being placed into, or null
	 * when it cannot be resolved. 1.21.11 has no {@code dimension()} on
	 * {@code WorldGenRegion}/{@code WorldGenLevel}, so go through the owning
	 * {@code ServerLevel} - the same route {@link #serverOf} uses.
	 */
	private static String dimensionKeyOf(WorldGenLevel level) {
		try {
			if (level instanceof net.minecraft.server.level.WorldGenRegion region) {
				return VpsCompat.registryKeyId(region.getLevel().dimension());
			}
			if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
				return VpsCompat.registryKeyId(serverLevel.dimension());
			}
		} catch (Throwable t) {
			return null;
		}
		return null;
	}

	/**
	 * T5 path: nothing may move at feature placement, so a seat with a RIGID
	 * decor piece on top pins at the level that piece actually renders at (the
	 * phase-2 plaza precedent: the pieces are the rigid truth); a bare seat
	 * rides the solve freely.
	 */
	private static void pinSeatsWithDecor(RoadNetwork network, List<StructurePiece> pieces) {
		for (RoadCell cell : network.cells) {
			if (!cell.seat) {
				continue;
			}
			for (StructurePiece piece : pieces) {
				if (!(piece instanceof PoolElementStructurePiece pool)
						|| (!(pool.getElement() instanceof SinglePoolElement)
								&& !(pool.getElement() instanceof FeaturePoolElement))) {
					continue;
				}
				BoundingBox box = piece.getBoundingBox();
				if (box.minX() != cell.x || box.maxX() != cell.x
						|| box.minZ() != cell.z || box.maxZ() != cell.z) {
					continue;
				}
				cell.plazaPinned = true;
				cell.plazaPinP = 2 * box.minY();
				break;
			}
		}
	}

	private static boolean hasStreetPiece(StructureStart start, VillagePathSmoothedConfig config) {
		for (StructurePiece piece : start.getPieces()) {
			if (RoadCollector.isStreetPiece(piece, config)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The template manager carried by the first pool-element piece (the same
	 * route RoadCollector uses), or null - the T5 path needs it for the
	 * template door scan, and a null degrades the doors to the junction
	 * fallback instead of failing the solve.
	 */
	private static StructureTemplateManager templateManagerOf(List<StructurePiece> pieces) {
		for (StructurePiece piece : pieces) {
			if (piece instanceof PoolElementStructurePiece) {
				try {
					return ((PoolElementStructurePieceVpsAccess) piece).village_path_smoothed$getTemplateManager();
				} catch (Throwable t) {
					return null;
				}
			}
		}
		return null;
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
			// plan 2026-10-16: nothing may move at feature placement, so a seat
			// with a RIGID decor piece on top pins at the level that piece
			// actually renders at (the phase-2 plaza precedent: the pieces are
			// the rigid truth); a bare seat rides the solve freely
			pinSeatsWithDecor(network, start.getPieces());
			// plan C, T5 path: nothing may move at feature placement, so the plaza
			// groups pin at the level their rigid pieces actually render at - the
			// solve and the world agree by construction
			network.applyPlazaPins(false);
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
		solution.doorFronts = BuildingComponents.doorFronts(start.getPieces(),
				BuildingComponents.templateDoors(start.getPieces(), templateManagerOf(start.getPieces())));

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

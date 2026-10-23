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
	/** Placement hooks that saw a zombie village and deliberately did nothing. */
	public static final AtomicLong ZOMBIE_PLACEMENTS_EXCLUDED = new AtomicLong();
	/** Recognised decor pieces excluded from plan A (seat decor, house lamps, unresolved). */
	public static final AtomicLong STREET_DECOR_PROTECTED_PIECES = new AtomicLong();
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
	// R1-1 (four-symptom fix plan 2026-10-16): a plaza group whose dy was pushed
	// back toward zero by the overlap or world-height guard. The piece can no
	// longer render the pinned level, so the phase-2 re-pin must run even when
	// nothing else moved - this counter is how often that guard fires.
	public static final AtomicLong PLAZA_OVERLAP_GUARDS = new AtomicLong();
	// R2-A (2026-10-16): a rigid component whose baseY would have been taken from
	// a 1x1 decor piece (the light/decoration hanging on the house's up jigsaw)
	// under the old upper-median rule. The fix excludes 1x1 pieces from the
	// median; a non-zero count is how many components that actually moved.
	public static final AtomicLong BASEY_DECOR_UPPER = new AtomicLong();
	// Door/basis fix plan 2026-10-17 (user decisions D1-D5). The base is now
	// the MAIN building piece's minY + groundLevelDelta; this counts components
	// whose other non-1x1 pieces disagree with it (a merged/anomalous RIGID
	// component), measured instead of silently averaged.
	public static final AtomicLong BASE_PLANE_MISMATCH = new AtomicLong();
	/** Lower double-block halves rejected because they are not in {@code #minecraft:doors} (tall grass, large fern). */
	public static final AtomicLong NON_DOOR_LOWER_HALF_SKIPPED = new AtomicLong();
	/** Templates without a {@code building_entrance} jigsaw: every real door is treated as a main door. */
	public static final AtomicLong MAIN_DOOR_NO_ENTRANCE = new AtomicLong();
	/** Main doors selected (a double door counts two). */
	public static final AtomicLong MAIN_DOOR_SELECTED = new AtomicLong();
	/** Corner tie of the door wall normal that could not be resolved by the entrance front/axis. */
	public static final AtomicLong DOOR_NORMAL_AMBIGUOUS = new AtomicLong();
	/** Seat columns rejected as a door target (D3: a building never aligns to a decor seat). */
	public static final AtomicLong SEAT_TARGET_EXCLUDED = new AtomicLong();
	/** Cells rejected because they lie in a protected footprint or in the writer's 3D building box. */
	public static final AtomicLong DOOR_TARGET_INSIDE_BUILDING = new AtomicLong();
	/** Cells rejected because they are on the door column or behind the door wall. */
	public static final AtomicLong DOOR_TARGET_BEHIND = new AtomicLong();
	/** Main doors with no eligible outward road cell (the building is not moved for them, D4). */
	public static final AtomicLong NO_FRONT_ROAD = new AtomicLong();
	/** Railing exemptions that fell back to the door wall normal because no road target existed. */
	public static final AtomicLong DOOR_FRONT_FALLBACK = new AtomicLong();
	/** Components the second authoritative alignment moved after the final re-solve (D5). */
	public static final AtomicLong SECOND_ALIGN_MOVED = new AtomicLong();
	/** Main-door target cells whose XZ or solved surface changed between the first and the final network. */
	public static final AtomicLong DOOR_TARGET_CHANGED_BY_RESOLVE = new AtomicLong();
	// Audit 2026-10-20 (decisions 1/2): actual street-decor footprints.
	/** Decor pieces recognised by a seat target pool (lamps, platforms, stairs, cats). */
	public static final AtomicLong STREET_DECOR_PIECES = new AtomicLong();
	/** Actual decor template blocks protected block-by-block (not the bbox). */
	public static final AtomicLong STREET_DECOR_BLOCKS = new AtomicLong();
	/** Road cells pinned to a multi-block decor's full base footprint (T5). */
	public static final AtomicLong STREET_DECOR_PINNED_CELLS = new AtomicLong();
	/** Multi-block decor whose seat/footprint level still disagrees after the second alignment. */
	public static final AtomicLong DECOR_SEAT_SYNC_MISMATCH = new AtomicLong();
	// Audit 2026-10-21 (fixture connections and connected base footprint):
	// the connection is the 4-adjacency between solve cells, not proximity.
	/** Multi-block decor's connected base footprints turned into movable/immovable flat units. */
	public static final AtomicLong DECOR_BASE_GROUPS = new AtomicLong();
	/** Solve cells inside those connected base footprints (seat + actual + air columns over road). */
	public static final AtomicLong DECOR_BASE_GROUP_CELLS = new AtomicLong();
	/** Multi-block decor pieces moved onto their final base group level (T1.5). */
	public static final AtomicLong DECOR_SETTLED_PIECES = new AtomicLong();
	/** Settles clamped by the world height (the decor could not reach its base level). */
	public static final AtomicLong DECOR_SETTLE_CLAMPED = new AtomicLong();
	/** Decor pieces with at least one horizontally adjacent external solve cell more than half a block from baseWalkY. */
	public static final AtomicLong DECOR_ROAD_MISMATCH_PIECES = new AtomicLong();
	/** Worst such difference, in half-block units. */
	public static final AtomicLong DECOR_ROAD_MISMATCH_WORST = new AtomicLong();
	/** Decor pieces with no 4-adjacent solve cell outside their own base footprint. */
	public static final AtomicLong DECOR_ROAD_ISOLATED_PIECES = new AtomicLong();
	/** Seat cells with no 4-adjacent mask cell: the solver has no edge to constrain them. */
	public static final AtomicLong DECOR_SEATS_ISOLATED = new AtomicLong();
	/** Decor candidates that matched a seat pool but whose template/blocks could not be resolved. */
	public static final AtomicLong DECOR_RESOLVE_FAILED = new AtomicLong();
	// 2026-10-22: structure-ground overlaps (street fixture bases, farmland/scenery).
	/** Overlap cells whose fixture base was already a road cell (the new fixture overlap unit). */
	public static final AtomicLong FIXTURE_BASE_OVERLAP_CELLS = new AtomicLong();
	/** Fixed-shape farm y=0 ground cells that overlap a road cell. */
	public static final AtomicLong FARM_OVERLAP_CELLS = new AtomicLong();
	/** Overlap cells written as their structure ground instead of a road. */
	public static final AtomicLong GROUND_OVERLAP_CELLS_WRITTEN = new AtomicLong();
	/** Overlap units the writer actually shifted. */
	public static final AtomicLong GROUND_OVERLAP_MOVED_UNITS = new AtomicLong();
	/** Above-ground structure blocks moved with a shifted overlap surface. */
	public static final AtomicLong GROUND_OVERLAP_BLOCKS_MOVED = new AtomicLong();
	// R2-C (2026-10-16): a SERVED component whose door road cells span more than
	// the half-block tolerance can bridge, so the shift came from the median
	// fallback instead of the legal interval. Measurement for the minimax
	// decision - no geometry change.
	public static final AtomicLong ALIGN_INTERVAL_FALLBACK = new AtomicLong();
	// R3-1 (2026-10-16): a door whose "door -> nearest road cell" direction
	// differs from the old footprint-main-axis outward direction. The railing
	// exemption now anchors on the road direction; this is the frequency of the
	// mismatch that made the old front miss the real doorway.
	public static final AtomicLong DOOR_FRONT_DIR_MISMATCH = new AtomicLong();
	// Debug door signs (user request 2026-10-16, opt-in since 2026-10-17): one standing sign
	// per template door of a movable building. WITHOUT_ROAD counts doors whose
	// village network held no road cell at all (no second line, no sign).
	public static final AtomicLong DOOR_SIGNS_PLACED = new AtomicLong();
	public static final AtomicLong DOOR_SIGNS_WITHOUT_ROAD = new AtomicLong();
	// Debug seam signs (user request 2026-10-16): seam cells of the solved mask
	// whose |dP| against the different-source neighbour is more than half a block
	// (a residual with a named cause). SEAM_SIGNS counts every sign emitted.
	public static final AtomicLong SEAM_SIGNS_COMPUTED = new AtomicLong();
	public static final AtomicLong SEAM_SIGNS_PLACED = new AtomicLong();
	/** Seam signs whose target cell did not yield a sign block entity at write time (diagnostic). */
	public static final AtomicLong SEAM_SIGNS_NO_BE = new AtomicLong();
	public static final AtomicLong SEAM_STEP_VIOLATIONS = new AtomicLong();

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
			VpsPlacementContext.push(solution.network, solution.pileBlocks);
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
				solution.buildingBoxes, solution.decorBlocks, solution.decorBaseBlocks,
				solution.pileBlocks, solution.doorFronts, solution.doorSigns, solution.seamSigns,
				String.valueOf(start.getChunkPos()));
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
		if (RoadCollector.isZombieVillage(start)) {
			// User decision: zombie villages are excluded as a whole. Returning
			// null means no mask, no y0 suppression and no writer, so the vanilla
			// zombie street layer is left exactly as generated.
			ZOMBIE_PLACEMENTS_EXCLUDED.incrementAndGet();
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
		if (RoadCollector.isZombieVillage(start)) {
			// The T1.5 pass must not move any zombie-village building either; the
			// FEATURES hook later sees the same exclusion and writes nothing.
			return;
		}
		if (!hasStreetPiece(start, config)) {
			return;
		}
		StructureStartVpsAccess access = (StructureStartVpsAccess) (Object) start;
		if (access.village_path_smoothed$getSolution() != null) {
			return; // idempotent (F10)
		}
		VpsVillageSolution solution = T15Planner.plan(start, generator, randomState, templateManager, chunkPos, heightAccessor, config,
				server.registryAccess());
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
	 * T5 path: nothing may move at feature placement, so a seat with a decor
	 * piece on top pins at the level that piece actually renders at (the
	 * phase-2 plaza precedent: the pieces are the rigid truth); a bare seat
	 * rides the solve freely.
	 *
	 * <p>Audit 2026-10-20 decision 2: the pin is {@code 2 * baseWalkY}
	 * ({@code bbox.minY + groundLevelDelta}), never the bare {@code 2 *
	 * bbox.minY} - the latter is one block low for {@code taiga_decoration_1}
	 * whose platform is a sunken layer. Identified 1x1 decor uses baseWalkY;
	 * a multi-block decor's FULL base footprint is pinned flat by
	 * {@link StreetDecor#pinBaseFootprints}. A 1x1 feature piece
	 * (pile/tree/flower) keeps the historical anchor-level pin.
	 *
	 * @return number of cells pinned
	 */
	private static int pinSeatsWithDecor(RoadNetwork network, List<StructurePiece> pieces,
			StreetDecor.Resolved decor) {
		int pinned = 0;
		for (RoadCell cell : network.cells) {
			if (!cell.seat) {
				continue;
			}
			long column = RoadCell.packedXZ(cell.x, cell.z);
			boolean pinnedHere = false;
			for (StreetDecor.Piece piece : decor.pieces) {
				if (piece.multiBlock || !piece.seatColumns.contains(column)) {
					continue;
				}
				cell.plazaPinned = true;
				cell.plazaPinP = 2 * piece.baseWalkY;
				pinnedHere = true;
				break;
			}
			if (pinnedHere) {
				pinned++;
				continue;
			}
			for (int i = 0; i < pieces.size(); i++) {
				StructurePiece piece = pieces.get(i);
				if (decor.pieceIndices.contains(i)) {
					continue; // an identified 1x1 decor was handled above
				}
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
				pinned++;
				break;
			}
		}
		return pinned;
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
		List<StructurePiece> pieces = start.getPieces();
		StructureTemplateManager templateManager = templateManagerOf(pieces);
		// Audit 2026-10-20: identify the actual decor footprint. T5 never moves a
		// piece, so this footprint is final as-is.
		StreetDecor.Resolved decor = StreetDecor.identify(pieces, network, templateManager, level.registryAccess());
		STREET_DECOR_PIECES.addAndGet(decor.pieces.size());
		STREET_DECOR_PROTECTED_PIECES.addAndGet(decor.protectedPieceIndices.size());
		STREET_DECOR_BLOCKS.addAndGet(decor.blocks.size());
		if (config.roadSmoothing.enabled) {
			network = StreetDecor.addFixtureBases(network, decor, config.roadSmoothing.maxExactSectionWidth, false);
			network = StreetDecor.addFarmGrounds(network, pieces, templateManager,
					config.roadSmoothing.maxExactSectionWidth);
			// plan 2026-10-16: nothing may move at feature placement, so a seat
			// with a RIGID decor piece on top pins at the level that piece
			// actually renders at (the phase-2 plaza precedent: the pieces are
			// the rigid truth); a bare seat rides the solve freely.
			// Audit 2026-10-20 decision 2: a multi-block decor pins its FULL base
			// footprint (seat, actual blocks and the air columns over road) at
			// baseWalkY - never the bare bbox.minY, and never only the actual
			// block columns.
			STREET_DECOR_PINNED_CELLS.addAndGet(pinSeatsWithDecor(network, pieces, decor));
			// plan C, T5 path: nothing may move at feature placement, so the plaza
			// groups pin at the level their rigid pieces actually render at - the
			// solve and the world agree by construction
			network.applyPlazaPins(false);
			STREET_DECOR_PINNED_CELLS.addAndGet(StreetDecor.pinBaseFootprints(decor, network));
			RoadSmoother.SolveStats stats = RoadSmoother.solve(network, paramsFor(config));
			accumulateSolveStats(stats);
			// User decision 2026-10-21: connection is the 4-adjacency between
			// solve cells; count adjacent residuals and pieces with no external edge.
			StreetDecor.recordIsolatedSeats(network);
			StreetDecor.recordRoadMismatch(decor, network);
		}
		if (!network.cells.isEmpty() && network.arms.isEmpty()) {
			VILLAGES_WITHOUT_ARMS.incrementAndGet();
			VillagePathSmoothed.LOGGER.warn("[VPS] village at {}: {} road cells but 0 arms - every street is wider than maxExactSectionWidth ({}), the whole village degrades into {} giant region(s) and the exact arm DP never runs",
					start.getChunkPos(), network.cells.size(), config.roadSmoothing.maxExactSectionWidth, network.regions.size());
		}
		VpsVillageSolution solution = new VpsVillageSolution(network);
		solution.solved = true;
		// T5 never moves buildings, so the boxes are already final here. Decor is
		// not a plan-A box any more; its actual blocks are protected by position.
		int[][] t5BuildingBoxes = BuildingComponents.protectedBoxes(pieces, decor.protectedPieceIndices);
		solution.buildingBoxes = t5BuildingBoxes;
		solution.decorBlocks = decor.blocks;
		java.util.Map<Long, String> t5BaseBlocks = new java.util.HashMap<>(decor.fixtureBaseBlocks);
		t5BaseBlocks.putAll(decor.baseBlocks);
		solution.decorBaseBlocks = t5BaseBlocks;
		BuildingComponents.TemplateScan t5Scan = BuildingComponents.scanTemplates(pieces, templateManager);
		List<BuildingComponents.Component> t5Components = BuildingComponents.group(pieces);
		// Door/basis fix plan 2026-10-17: the T5 path uses the same main doors,
		// the same filtered door target and the same sign format as T1.5; no
		// building moves here, so the current boxes are the eligibility boxes.
		// Decor footprint columns are excluded from door targets but not from
		// plan A (audit 2026-10-20 decision 1).
		int[][] t5Exclusion = T15Planner.mergeBoxesForT5(t5BuildingBoxes, decor.columnBoxes);
		List<List<DoorSigns.DoorTarget>> t5Targets = new java.util.ArrayList<>(t5Components.size());
		for (BuildingComponents.Component component : t5Components) {
			t5Targets.add(T15Planner.doorTargetsFor(component, pieces, t5Scan, network, t5Exclusion, true));
		}
		solution.doorFronts = T15Planner.doorFrontsFor(t5Components, pieces, t5Targets, network);
		solution.doorSigns = T15Planner.computeDoorSigns(t5Components, t5Targets, network, config);
		solution.seamSigns = T15Planner.computeSeamSigns(network, config);

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

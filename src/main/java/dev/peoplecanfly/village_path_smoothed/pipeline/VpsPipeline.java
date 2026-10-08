/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import dev.peoplecanfly.village_path_smoothed.compat.VpsCompat;
import dev.peoplecanfly.village_path_smoothed.duck.StructureStartVpsAccess;
import dev.peoplecanfly.village_path_smoothed.terrain.VpsGenContext;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point called from MixinStructureStart after every
 * StructureStart#placeInChunk. Solves each village once (cached on the start
 * instance) and rewrites the road blocks that fall into the current chunk's
 * writable area.
 */
public final class VpsPipeline {
	public static final AtomicLong VILLAGES_SOLVED = new AtomicLong();
	public static final AtomicLong SOLVE_NANOS = new AtomicLong();
	public static final AtomicLong ROAD_CELLS_WRITTEN = new AtomicLong();
	public static final AtomicLong KERBS_PLACED = new AtomicLong();
	public static final AtomicLong RAILINGS_PLACED = new AtomicLong();
	public static final AtomicLong SCAFFOLDS_PLACED = new AtomicLong();

	private VpsPipeline() {
	}

	public static void onStructurePlacedInChunk(
			StructureStart start,
			WorldGenLevel level,
			ChunkGenerator generator,
			BoundingBox chunkBB,
			ChunkPos chunkPos
	) {
		VillagePathSmoothedConfig config = VillagePathSmoothedConfig.get();
		if (start == null || !start.isValid()) {
			return;
		}
		// the per-world switch is authoritative; the global config "enabled" only
		// seeds the default for worlds without their own file (VpsWorldSettings)
		MinecraftServer server = serverOf(level);
		if (server == null || !VpsWorldSettings.isEnabled(server)) {
			return;
		}
		boolean isVanillaVillage = VpsCompat.isVanillaVillageStructure(level, start.getStructure());
		if (!isVanillaVillage && !RoadCollector.hasCustomRegex(config)) {
			return; // fast path: other structures cost nothing
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
		if (solution.network.cells.isEmpty()) {
			return;
		}

		VpsGenContext ctx = VpsGenContext.capture(level, generator, chunkPos);
		RoadWriter.write(solution.network, ctx, chunkBB, config);
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
		// Analysis report section 3.1 / fix 1: the real-heightmap reliability
		// window is guaranteed by the vanilla chunk pyramid only +/-1 chunks
		// around the chunk currently being generated (FEATURES needs CARVERS at
		// distance 1, only STRUCTURE_STARTS at distance 2..8), so the probe
		// center must be the triggering chunk - NOT the village start chunk.
		// The start chunk's neighbourhood may still be STRUCTURE_STARTS-only
		// air whose lazily primed heightmaps read as the dimension floor; that
		// garbage baseline used to sink whole roads. Noise heights outside the
		// window are a pure function of x/z, so the single cached solve stays
		// consistent regardless of which chunk of the village triggers it.
		VpsGenContext ctx = VpsGenContext.capture(level, generator, triggerChunk);
		RoadNetwork network = RoadCollector.collect(start, ctx, config);
		if (config.roadSmoothing.enabled) {
			RoadSmoother.solve(network, paramsFor(config));
		}
		VpsVillageSolution solution = new VpsVillageSolution(network);
		solution.solved = true;

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

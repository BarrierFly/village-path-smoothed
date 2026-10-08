/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.state.BlockState;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Terrain access during world generation.
 *
 * Height sources (plan section 4.4, strategy P3):
 *  - the real WORLD_SURFACE_WG heightmap, reliable only for columns whose chunk
 *    is within +/-1 chunks of the chunk currently being generated;
 *  - the noise base height everywhere else (a pure function of x/z, so the
 *    single solve of a village stays consistent no matter which chunk of the
 *    village triggers it). Unlike the heightmap the noise height ignores
 *    carved terrain, but it does include structure bearding and water surfaces.
 *
 * Heights returned by {@link #surfaceTopY} are "first air above surface",
 * matching vanilla GravityProcessor's reading; the vanilla road block sits at
 * surfaceTopY - 1 and the walk surface is surfaceTopY.
 */
public final class VpsGenContext {
	public final WorldGenLevel level;
	public final ChunkGenerator generator;
	public final RandomState randomState;
	public final ChunkPos center;
	/** {minX, maxX, minZ, maxZ} of RIGID piece boxes (beard sources), used to compensate the noise probe next to buildings. */
	private int[][] beardBoxes;

	private VpsGenContext(WorldGenLevel level, ChunkGenerator generator, RandomState randomState, ChunkPos center, int[][] beardBoxes) {
		this.level = level;
		this.generator = generator;
		this.randomState = randomState;
		this.center = center;
		this.beardBoxes = beardBoxes;
	}

	public static VpsGenContext capture(WorldGenLevel level, ChunkGenerator generator, ChunkPos center) {
		return capture(level, generator, center, null);
	}

	public static VpsGenContext capture(WorldGenLevel level, ChunkGenerator generator, ChunkPos center, int[][] beardBoxes) {
		RandomState randomState = null;
		try {
			if (level instanceof WorldGenRegion region) {
				randomState = region.getLevel().getChunkSource().randomState();
			}
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.warn("[VPS] Could not capture RandomState; noise fallback heights disabled", t);
		}
		return new VpsGenContext(level, generator, randomState, center, beardBoxes);
	}

	/** Late-sets the beard source boxes once the collector has walked the pieces. */
	public void setBeardBoxes(int[][] boxes) {
		this.beardBoxes = boxes;
	}

	public int getMinY() {
		//#if MC >= 12103
		//$$ return this.level.getMinY();
		//#else
		return this.level.getMinBuildHeight();
		//#endif
	}

	public int getMaxY() {
		//#if MC >= 12103
		//$$ return this.level.getMinY() + this.level.getHeight() - 1;
		//#else
		return this.level.getMaxBuildHeight() - 1;
		//#endif
	}

	/** True when the column's chunk is within the reliably readable area. */
	public boolean hasRealHeight(int x, int z) {
		int cx = x >> 4;
		int cz = z >> 4;
		//#if MC >= 260000
		//$$ int ccx = this.center.x();
		//$$ int ccz = this.center.z();
		//#else
		int ccx = this.center.x;
		int ccz = this.center.z;
		//#endif
		return Math.abs(cx - ccx) <= 1 && Math.abs(cz - ccz) <= 1;
	}

	/** Real heightmap height; only call when {@link #hasRealHeight} is true. */
	public int realSurfaceTopY(int x, int z) {
		return this.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
	}

	/** Noise-based height; returns {@link #NOISE_UNAVAILABLE} when no RandomState is available. */
	public int noiseSurfaceTopY(int x, int z) {
		if (this.randomState == null) {
			return NOISE_UNAVAILABLE;
		}
		try {
			return this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.level, this.randomState);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] noise height probe failed at {} {}", x, z, t);
			return NOISE_UNAVAILABLE;
		}
	}

	/**
	 * Number of probes where the real heightmap was distrusted as a floor value
	 * (see {@link HeightSanity#shouldDistrustRealHeight}).
	 */
	public static final AtomicLong FLOOR_GARBAGE_HITS = new AtomicLong();
	/** Sentinel for "no noise height": Integer.MIN_VALUE cannot collide with a legitimate first-air-above-surface height (unlike the old -1 sentinel, which is a real height in min_y=-64 worlds). */
	public static final int NOISE_UNAVAILABLE = Integer.MIN_VALUE;
	private static volatile long lastFloorGarbageWarnNanos;

	private static void warnFloorGarbage(int x, int z, int real, int noise) {
		long now = System.nanoTime();
		if (now - lastFloorGarbageWarnNanos < 30_000_000_000L) {
			return;
		}
		lastFloorGarbageWarnNanos = now;
		VillagePathSmoothed.LOGGER.warn("[VPS] heightmap probe at {} {} returned {} (within {} of the dimension floor), which looks like a lazily-primed empty chunk; using the noise height {} instead",
				x, z, real, HeightSanity.FLOOR_GARBAGE_MARGIN, noise);
	}

	/**
	 * Noise-based height with structure-beard compensation. Returns
	 * {@link #NOISE_UNAVAILABLE} when no RandomState is available.
	 *
	 * The raw {@code getBaseHeight} evaluates noise with a no-op beardifier,
	 * while real terrain next to RIGID pieces (buildings) is bearded upward by
	 * roughly one block; without the compensation road segments anchored on
	 * the raw noise sink by that block next to buildings (too small for a
	 * kerb: bare dirt walls, report "parts of roads sit 1 block low"). Only
	 * raises, never lowers; disabled with beardCompensation < 0.
	 */
	public int noiseSurfaceTopY(int x, int z, VillagePathSmoothedConfig config) {
		int raw = noiseSurfaceTopY(x, z);
		if (raw == NOISE_UNAVAILABLE || this.beardBoxes == null || config.roadSmoothing.beardCompensation <= 0) {
			return raw;
		}
		boolean near = BeardCompensation.isNearBuilding(this.beardBoxes, x, z, config.roadSmoothing.beardRadius);
		return BeardCompensation.compensate(raw, near, config.roadSmoothing.beardCompensation);
	}

	/**
	 * Best available height for a column. Real height inside +/-1 chunks,
	 * compensated noise height outside; falls back to whatever works.
	 */
	public int surfaceTopY(int x, int z, VillagePathSmoothedConfig config) {
		if (hasRealHeight(x, z)) {
			try {
				int real = realSurfaceTopY(x, z);
				// the noise probe is not free, so only pay for the cross-check
				// when the real value sits suspiciously close to the floor
				if (real - this.getMinY() <= HeightSanity.FLOOR_GARBAGE_MARGIN) {
					int noise = noiseSurfaceTopY(x, z, config);
					if (HeightSanity.shouldDistrustRealHeight(real, noise, this.getMinY())) {
						FLOOR_GARBAGE_HITS.incrementAndGet();
						warnFloorGarbage(x, z, real, noise);
						return noise;
					}
				}
				return real;
			} catch (Throwable t) {
				// fall through to the noise probe
			}
		}
		int noise = noiseSurfaceTopY(x, z, config);
		if (noise != NOISE_UNAVAILABLE) {
			return noise;
		}
		return realSurfaceTopY(x, z); // may throw; caller treats that as fatal for this chunk
	}

	public BlockState blockState(int x, int y, int z) {
		return this.level.getBlockState(new BlockPos(x, y, z));
	}

	public LevelReader levelReader() {
		return this.level;
	}
}

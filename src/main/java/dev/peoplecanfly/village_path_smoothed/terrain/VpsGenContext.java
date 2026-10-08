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

	private VpsGenContext(WorldGenLevel level, ChunkGenerator generator, RandomState randomState, ChunkPos center) {
		this.level = level;
		this.generator = generator;
		this.randomState = randomState;
		this.center = center;
	}

	public static VpsGenContext capture(WorldGenLevel level, ChunkGenerator generator, ChunkPos center) {
		RandomState randomState = null;
		try {
			if (level instanceof WorldGenRegion region) {
				randomState = region.getLevel().getChunkSource().randomState();
			}
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.warn("[VPS] Could not capture RandomState; noise fallback heights disabled", t);
		}
		return new VpsGenContext(level, generator, randomState, center);
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
	 * Best available height for a column. Real height inside +/-1 chunks,
	 * noise height outside; falls back to whatever works.
	 */
	public int surfaceTopY(int x, int z) {
		if (hasRealHeight(x, z)) {
			try {
				int real = realSurfaceTopY(x, z);
				// the noise probe is not free, so only pay for the cross-check
				// when the real value sits suspiciously close to the floor
				if (real - this.getMinY() <= HeightSanity.FLOOR_GARBAGE_MARGIN) {
					int noise = noiseSurfaceTopY(x, z);
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
		int noise = noiseSurfaceTopY(x, z);
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

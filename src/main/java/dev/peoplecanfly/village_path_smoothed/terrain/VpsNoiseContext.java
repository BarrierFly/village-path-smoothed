/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothed;
import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-noise height source for the T1.5 forward decision (structure assembly,
 * before any level is available). Reads only {@code getBaseHeight} (a pure
 * function of x/z) and never touches the world; anchor detection uses the noise
 * water column. The beard compensation is still applied, but the beard boxes
 * are the pre-shift RIGID piece boxes (the shift itself is what the T1.5 pass
 * decides).
 *
 * <p><b>Caching.</b> {@code getBaseHeight} is a full multi-octave noise
 * evaluation, and the collector asks for the same column repeatedly: once
 * through {@link #surfaceTopY} and twice more inside {@link #noiseHasWaterColumn},
 * then the whole village again on the post-shift re-collect. Since {@link
 * #hasRealHeight} is constantly false here (no level exists at structure
 * assembly), <em>every</em> road cell pays the full noise cost - tens of
 * thousands of evaluations for a large village. The raw, UNCOMPENSATED results
 * are therefore memoised per context: they are a pure function of x/z and do not
 * change when a piece moves, whereas the beard compensation layered on top of
 * them does - so the cache is filled strictly below the compensation.
 */
public final class VpsNoiseContext implements VpsProbe {
	/** Room for the default 20k-cell village cap plus the anchor lookups. */
	private static final int PROBE_CACHE_LIMIT = 1 << 17;

	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final LevelHeightAccessor height;
	private int[][] beardBoxes;
	private final Map<Long, Integer> surfaceCache = new HashMap<>();
	private final Map<Long, Boolean> waterColumnCache = new HashMap<>();

	public VpsNoiseContext(ChunkGenerator generator, RandomState randomState, LevelHeightAccessor height) {
		this.generator = generator;
		this.randomState = randomState;
		this.height = height;
	}

	@Override
	public int getMinY() {
		//#if MC >= 12103
		//$$ return this.height.getMinY();
		//#else
		return this.height.getMinBuildHeight();
		//#endif
	}

	@Override
	public int getMaxY() {
		//#if MC >= 12103
		//$$ return this.height.getMaxY();
		//#else
		return this.height.getMaxBuildHeight() - 1;
		//#endif
	}

	@Override
	public boolean hasRealHeight(int x, int z) {
		return false;
	}

	@Override
	public int realSurfaceTopY(int x, int z) {
		throw new UnsupportedOperationException("no real heightmap during structure assembly");
	}

	@Override
	public void setBeardBoxes(int[][] boxes) {
		this.beardBoxes = boxes;
	}

	/** Packs an x/z column into a cache key. Duplicated from RoadCell (terrain must not import pipeline). */
	private static long columnKey(int x, int z) {
		return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
	}

	private int rawNoise(int x, int z) {
		if (this.randomState == null) {
			return VpsGenContext.NOISE_UNAVAILABLE;
		}
		long key = columnKey(x, z);
		Integer cached = this.surfaceCache.get(key);
		if (cached != null) {
			return cached;
		}
		int value;
		try {
			value = this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.height, this.randomState);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] T1.5 noise probe failed at {} {}", x, z, t);
			value = VpsGenContext.NOISE_UNAVAILABLE;
		}
		if (this.surfaceCache.size() >= PROBE_CACHE_LIMIT) {
			this.surfaceCache.clear();
		}
		this.surfaceCache.put(key, value);
		return value;
	}

	@Override
	public int surfaceTopY(int x, int z, VillagePathSmoothedConfig config) {
		int raw = rawNoise(x, z);
		if (raw == VpsGenContext.NOISE_UNAVAILABLE || this.beardBoxes == null || config.roadSmoothing.beardCompensation <= 0) {
			return raw;
		}
		if ("beard_thin".equals(config.roadSmoothing.beardKernel)) {
			return BeardCompensation.compensateBeardThin(raw, this.beardBoxes, x, raw, z,
					config.roadSmoothing.beardKernelBlocksPerDensity);
		}
		boolean near = BeardCompensation.isNearBuilding(this.beardBoxes, x, z, config.roadSmoothing.beardRadius);
		return BeardCompensation.compensate(raw, near, config.roadSmoothing.beardCompensation);
	}

	@Override
	public boolean noiseHasWaterColumn(int x, int z) {
		if (this.randomState == null) {
			return false;
		}
		long key = columnKey(x, z);
		Boolean cached = this.waterColumnCache.get(key);
		if (cached != null) {
			return cached;
		}
		boolean hasWater;
		try {
			int floor = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.height, this.randomState);
			// shared with rawNoise, so the surface half is a cache hit on every
			// column the collector has already asked about
			int surface = rawNoise(x, z);
			hasWater = surface > floor;
		} catch (Throwable t) {
			hasWater = false;
		}
		if (this.waterColumnCache.size() >= PROBE_CACHE_LIMIT) {
			this.waterColumnCache.clear();
		}
		this.waterColumnCache.put(key, hasWater);
		return hasWater;
	}

	@Override
	public BlockState blockState(int x, int y, int z) {
		throw new UnsupportedOperationException("no world access during structure assembly");
	}
}

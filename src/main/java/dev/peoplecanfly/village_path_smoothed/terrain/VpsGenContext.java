/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import dev.peoplecanfly.village_path_smoothed.pipeline.ProbeWindow;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terrain access during world generation.
 *
 * <p>Height sources (plan section 4.4, strategy P3):
 *  - the real WORLD_SURFACE_WG heightmap, reliable only for columns whose chunk
 *    is within +/-1 chunks of the window anchor - which since fix plan v4 D2-c
 *    is the village's centre chunk, not the chunk that triggered the solve;
 *  - the noise base height everywhere else (a pure function of x/z, so the
 *    single solve of a village stays consistent no matter which chunk of the
 *    village triggers it). Unlike the heightmap the noise height ignores
 *    carved terrain and structure bearding (the probe's BeardifierMarker is a
 *    no-op - the beard compensation below exists for exactly that reason),
 *    but it does include water surfaces.
 *
 * <p>Scope note (fix plan v4 F14/F16): this window is a <b>write-stage</b>
 * property. The T1.5 solve runs during STRUCTURE_STARTS, where no chunk of the
 * village has terrain yet, so on that path nothing here is real - VpsNoiseContext
 * is used instead.
 *
 * <p>Heights returned by {@link #surfaceTopY} are "first air above surface",
 * matching vanilla GravityProcessor's reading; the vanilla road block sits at
 * surfaceTopY - 1 and the walk surface is surfaceTopY.
 */
public final class VpsGenContext implements VpsProbe {
	public final WorldGenLevel level;
	public final ChunkGenerator generator;
	public final RandomState randomState;
	/** Chunk that triggers the write; only the {@code ALL_NOISE} caliber and unanchored legacy callers care. */
	public final ChunkPos center;
	/** Chunk coordinates the probe window is centred on (fix plan v4 D2-c: the village centre). */
	private int centerX;
	private int centerZ;
	private boolean villageAnchored;
	/** fix plan v4 D2-b: {@code ALL_NOISE} disables the real-heightmap window entirely. */
	private final boolean realHeightCaliber;
	/** {minX, maxX, minZ, maxZ, minY, groundLevelDelta} of RIGID piece boxes (beard sources), used to compensate the noise probe next to buildings. */
	private int[][] beardBoxes;
	/**
	 * Memoised RAW {@code getBaseHeight} results. Outside the +/-1 chunk window
	 * every road cell falls through to the noise probe, and the collector asks
	 * for each column up to three times (the height itself plus the two halves
	 * of the water-column anchor check), so a village pays tens of thousands of
	 * multi-octave noise evaluations without this. Only the uncompensated value
	 * is cached: it is a pure function of x/z for a fixed RandomState, while the
	 * beard compensation layered on top of it depends on {@link #beardBoxes}.
	 */
	private final Map<Long, Integer> noiseSurfaceCache = new HashMap<>();
	private final Map<Long, Boolean> noiseWaterCache = new HashMap<>();

	private VpsGenContext(WorldGenLevel level, ChunkGenerator generator, RandomState randomState, ChunkPos center,
			boolean realHeightCaliber) {
		this.level = level;
		this.generator = generator;
		this.randomState = randomState;
		this.center = center;
		this.realHeightCaliber = realHeightCaliber;
		//#if MC >= 260000
		//$$ this.centerX = center.x();
		//$$ this.centerZ = center.z();
		//#else
		this.centerX = center.x;
		this.centerZ = center.z;
		//#endif
	}

	public static VpsGenContext capture(WorldGenLevel level, ChunkGenerator generator, ChunkPos center) {
		return capture(level, generator, center, true);
	}

	/**
	 * @param realHeightCaliber false for the {@code ALL_NOISE} caliber (fix plan
	 *        v4 D2-b): the solve reads the noise model everywhere and the real
	 *        heightmap stays a write-time-only input
	 */
	public static VpsGenContext capture(WorldGenLevel level, ChunkGenerator generator, ChunkPos center, boolean realHeightCaliber) {
		RandomState randomState = null;
		try {
			if (level instanceof WorldGenRegion region) {
				randomState = region.getLevel().getChunkSource().randomState();
			}
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.warn("[VPS] Could not capture RandomState; noise fallback heights disabled", t);
		}
		return new VpsGenContext(level, generator, randomState, center, realHeightCaliber);
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
		if (!this.realHeightCaliber) {
			return false; // fix plan v4 D2-b: ALL_NOISE never reads the real heightmap
		}
		return ProbeWindow.inWindow(x, z, centerChunkX(), centerChunkZ());
	}

	private int centerChunkX() {
		return this.centerX;
	}

	private int centerChunkZ() {
		return this.centerZ;
	}

	/**
	 * Anchors the probe window on the village instead of on the triggering chunk
	 * (fix plan v4 D2-c).
	 *
	 * <p>The village is solved once and cached on the StructureStart, but the
	 * window used to be centred on whichever chunk's {@code placeInChunk} got
	 * there first, so the same seed could produce two different roads depending
	 * on generation order - and the columns inside the window are real terrain,
	 * not a pure function of x/z, so the old comment claiming otherwise was wrong
	 * for exactly the columns that matter. The anchor is now a pure function of
	 * the village bounding box (see {@link ProbeWindow}, which deliberately has
	 * no trigger parameter).
	 */
	public void anchorOnVillage(BoundingBox box) {
		int[] anchor = ProbeWindow.centerChunk(box.minX(), box.maxX(), box.minZ(), box.maxZ());
		this.centerX = anchor[0];
		this.centerZ = anchor[1];
		this.villageAnchored = true;
	}

	/** True once {@link #anchorOnVillage} ran, i.e. the window no longer follows the trigger. */
	public boolean isVillageAnchored() {
		return this.villageAnchored;
	}

	/** Real heightmap height; only call when {@link #hasRealHeight} is true. */
	public int realSurfaceTopY(int x, int z) {
		return this.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
	}

	/** Room for the default 20k-cell village cap plus the anchor lookups. */
	private static final int PROBE_CACHE_LIMIT = 1 << 17;

	/** Packs an x/z column into a cache key. Duplicated from RoadCell (terrain must not import pipeline). */
	private static long columnKey(int x, int z) {
		return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
	}

	/** Noise-based height; returns {@link #NOISE_UNAVAILABLE} when no RandomState is available. */
	public int noiseSurfaceTopY(int x, int z) {
		if (this.randomState == null) {
			return NOISE_UNAVAILABLE;
		}
		long key = columnKey(x, z);
		Integer cached = this.noiseSurfaceCache.get(key);
		if (cached != null) {
			return cached;
		}
		int value;
		try {
			value = this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.level, this.randomState);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] noise height probe failed at {} {}", x, z, t);
			value = NOISE_UNAVAILABLE;
		}
		if (this.noiseSurfaceCache.size() >= PROBE_CACHE_LIMIT) {
			this.noiseSurfaceCache.clear();
		}
		this.noiseSurfaceCache.put(key, value);
		return value;
	}

	/**
	 * Noise water level probe: the ocean-floor height (terrain below the water)
	 * versus the world-surface height (which includes the water). When the
	 * surface is above the floor there is a water column at this x/z. Used as
	 * the out-of-window approximation for the water-crossing anchor detection
	 * (A2/F9); returns false when no RandomState is available.
	 */
	public boolean noiseHasWaterColumn(int x, int z) {
		if (this.randomState == null) {
			return false;
		}
		long key = columnKey(x, z);
		Boolean cached = this.noiseWaterCache.get(key);
		if (cached != null) {
			return cached;
		}
		boolean hasWater;
		try {
			int floor = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.level, this.randomState);
			// shared with noiseSurfaceTopY, so the surface half is a cache hit on
			// every column the collector has already asked about
			int surface = noiseSurfaceTopY(x, z);
			hasWater = surface > floor;
		} catch (Throwable t) {
			hasWater = false;
		}
		if (this.noiseWaterCache.size() >= PROBE_CACHE_LIMIT) {
			this.noiseWaterCache.clear();
		}
		this.noiseWaterCache.put(key, hasWater);
		return hasWater;
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
	 * so it does NOT see the terrain adaptation around RIGID pieces
	 * (buildings) at all. Two approximations exist (see BeardCompensation):
	 * the legacy flat +1 step inside beardRadius, and the real beard_thin
	 * closed form behind the beardKernel switch - the latter is not enabled
	 * by default because its density-to-blocks factor still needs in-game
	 * calibration (review 3 section 2.4 / 7-T6).
	 */
	public int noiseSurfaceTopY(int x, int z, VillagePathSmoothedConfig config) {
		int raw = noiseSurfaceTopY(x, z);
		if (raw == NOISE_UNAVAILABLE || this.beardBoxes == null || config.roadSmoothing.beardCompensation <= 0) {
			return raw;
		}
		if ("beard_thin".equals(config.roadSmoothing.beardKernel)) {
			return BeardCompensation.compensateBeardThin(raw, this.beardBoxes, x, raw, z,
					config.roadSmoothing.beardKernelBlocksPerDensity);
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

	/**
	 * "First air above the ground" found by scanning <b>down</b> from
	 * {@code fromY}: the surface a fill or a bridge actually rides on, immune to
	 * canopies and overhangs above the road. See {@link ColumnScan}.
	 *
	 * <p>Pass the road block's own Y as {@code fromY}: a standable block there
	 * means the road is cut into the terrain and needs no fill.
	 */
	public int groundSurfaceTopY(int x, int z, int fromY) {
		return ColumnScan.downwardSurfaceTopY(y -> isStandableGround(x, y, z), fromY, getMinY());
	}

	/**
	 * "First air above the ceiling" found by scanning <b>up</b> from
	 * {@code fromY}: the top of the contiguous terrain the road has to clear.
	 * When the block above the road is already air the result is {@code fromY},
	 * so nothing is cleared.
	 */
	public int ceilingSurfaceTopY(int x, int z, int fromY) {
		return ColumnScan.upwardSurfaceTopY(y -> isClearableTerrain(x, y, z), fromY, getMaxY());
	}

	/** Top of the contiguous solid run starting at {@code fromY} (for the kerb drop). */
	public int solidRunTopY(int x, int z, int fromY) {
		return ColumnScan.upwardSolidTopY(y -> isClearableTerrain(x, y, z), fromY, getMaxY());
	}

	/** True when a block can carry a road block: its top face is sturdy (slabs, snow layers, ... count). */
	public boolean isStandableGround(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		return this.level.getBlockState(pos).isFaceSturdy(this.level, pos, Direction.UP);
	}

	/**
	 * True when a block at the road's own level blocks a walker: not air, not a
	 * fluid, not replaceable vegetation. Used for the side-column probe that
	 * decides kerb (blocked) versus railing (open).
	 */
	public boolean isSolidAt(int x, int y, int z) {
		return isClearableTerrain(x, y, z);
	}

	/**
	 * The kerb/ceiling criterion (user decision 2026-10-17): a block counts when
	 * it has a non-empty collision shape or carries a fluid, regardless of its
	 * material. This is deliberately not a dirt/terrain whitelist: a leaf canopy,
	 * a slab or water all decide the wrap.
	 */
	public boolean hasCollisionOrFluid(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = this.level.getBlockState(pos);
		if (!state.getFluidState().isEmpty()) {
			return true;
		}
		return !state.getCollisionShape(this.level, pos).isEmpty();
	}

	/**
	 * Highest block at/above {@code fromY} whose collision shape is non-empty or
	 * which is a fluid, re-read at write time. Returns {@code fromY - 1} when the
	 * column has no such block above the start.
	 */
	public int highestCollisionOrFluidY(int x, int z, int fromY) {
		for (int y = getMaxY(); y >= fromY; y--) {
			if (hasCollisionOrFluid(x, y, z)) {
				return y;
			}
		}
		return fromY - 1;
	}

	/** Lowest collision/fluid block at/above {@code fromY}, or {@code Integer.MIN_VALUE} when there is none. */
	public int firstCollisionOrFluidY(int x, int z, int fromY) {
		for (int y = Math.max(fromY, getMinY()); y <= getMaxY(); y++) {
			if (hasCollisionOrFluid(x, y, z)) {
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Terrain the road writes over: not air, not fluid, not replaceable. */
	private boolean isClearableTerrain(int x, int y, int z) {
		BlockState state = this.level.getBlockState(new BlockPos(x, y, z));
		if (state.isAir() || state.canBeReplaced()) {
			return false;
		}
		return state.getFluidState().isEmpty();
	}

	public LevelReader levelReader() {
		return this.level;
	}
}

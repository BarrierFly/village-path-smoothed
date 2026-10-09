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

/**
 * Pure-noise height source for the T1.5 forward decision (structure assembly,
 * before any level is available). Reads only {@code getBaseHeight} (a pure
 * function of x/z) and never touches the world; anchor detection uses the noise
 * water column. The beard compensation is still applied, but the beard boxes
 * are the pre-shift RIGID piece boxes (the shift itself is what the T1.5 pass
 * decides).
 */
public final class VpsNoiseContext implements VpsProbe {
	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final LevelHeightAccessor height;
	private int[][] beardBoxes;

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

	private int rawNoise(int x, int z) {
		if (this.randomState == null) {
			return VpsGenContext.NOISE_UNAVAILABLE;
		}
		try {
			return this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.height, this.randomState);
		} catch (Throwable t) {
			VillagePathSmoothed.LOGGER.debug("[VPS] T1.5 noise probe failed at {} {}", x, z, t);
			return VpsGenContext.NOISE_UNAVAILABLE;
		}
	}

	@Override
	public int surfaceTopY(int x, int z, VillagePathSmoothedConfig config) {
		int raw = rawNoise(x, z);
		if (raw == VpsGenContext.NOISE_UNAVAILABLE || this.beardBoxes == null || config.roadSmoothing.beardCompensation <= 0) {
			return raw;
		}
		boolean near = BeardCompensation.isNearBuilding(this.beardBoxes, x, z, config.roadSmoothing.beardRadius);
		return BeardCompensation.compensate(raw, near, config.roadSmoothing.beardCompensation);
	}

	@Override
	public boolean noiseHasWaterColumn(int x, int z) {
		if (this.randomState == null) {
			return false;
		}
		try {
			int floor = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.height, this.randomState);
			int surface = this.generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, this.height, this.randomState);
			return surface > floor;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public BlockState blockState(int x, int y, int z) {
		throw new UnsupportedOperationException("no world access during structure assembly");
	}
}

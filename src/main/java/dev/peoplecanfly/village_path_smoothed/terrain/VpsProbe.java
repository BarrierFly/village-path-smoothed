/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Height source consumed by {@code RoadCollector}. Two implementations:
 * {@link VpsGenContext} (feature placement: real heightmaps inside +/-1 chunks,
 * compensated noise outside) and {@link VpsNoiseContext} (T1.5 forward decision:
 * pure noise, no readable world).
 */
public interface VpsProbe {
	int getMinY();

	int getMaxY();

	/** True when the column's chunk is within the reliably readable area. */
	boolean hasRealHeight(int x, int z);

	/** Real heightmap height; only call when {@link #hasRealHeight} is true. */
	int realSurfaceTopY(int x, int z);

	/** Best available height for a column. */
	int surfaceTopY(int x, int z, VillagePathSmoothedConfig config);

	BlockState blockState(int x, int y, int z);

	/** Noise water-column approximation used for anchor detection outside the readable window. */
	boolean noiseHasWaterColumn(int x, int z);

	/** Late-sets the beard source boxes once the collector has walked the pieces. */
	void setBeardBoxes(int[][] boxes);
}

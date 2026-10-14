/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.terrain.HeightSanity;

/**
 * Which height a collected road column uses (fix plan v4 D1-c).
 *
 * <p>Pure decision logic, so the "does a failed probe cost a cell?" rule is unit
 * tested instead of living inside the collector's try/catch ladder. The rule it
 * replaces dropped the column outright on three different failures, and dropping
 * a column is not a local cost: the mask splits in two, each half solves alone,
 * and the dropped column keeps a full-height vanilla road in between - a defect
 * every |dP| counter is blind to. A noisier height only costs deviation, so the
 * noise caliber is always tried before the column is given up.
 *
 * <p>No Minecraft imports: {@link HeightSanity} is pure and the sentinel is passed
 * in, so this loads in a plain JUnit run.
 */
public final class ProbeHeights {
	/** What the collector does with one probed column. */
	public enum Outcome {
		/** The primary probe's height is usable. */
		USE_PRIMARY,
		/** The primary probe failed; the noise height is used instead. */
		USE_NOISE,
		/** No height at all: the column is dropped and stays vanilla. */
		DROP
	}

	private ProbeHeights() {
	}

	/**
	 * @param primaryTopY the primary probe's height, or the unavailable sentinel
	 * @param noiseTopY the noise caliber's height, or the unavailable sentinel
	 * @param minY dimension floor, used to recognise a lazily-primed empty chunk
	 * @param unavailable the sentinel value both probes use for "no height"
	 */
	public static Outcome resolve(int primaryTopY, int noiseTopY, int minY, int unavailable) {
		if (isUsable(primaryTopY, minY, unavailable)) {
			return Outcome.USE_PRIMARY;
		}
		return isUsable(noiseTopY, minY, unavailable) ? Outcome.USE_NOISE : Outcome.DROP;
	}

	private static boolean isUsable(int topY, int minY, int unavailable) {
		return topY != unavailable && !HeightSanity.isExactFloorProbe(topY, minY);
	}
}
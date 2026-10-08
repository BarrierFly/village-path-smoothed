/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

/**
 * Pure decision helpers for height-probe sanity checks. No Minecraft types, so
 * unit tests can load this class without the game on the classpath.
 */
public final class HeightSanity {
	/**
	 * Real heightmap heights can still be garbage even inside the reliable
	 * window: a chunk that has only reached STRUCTURE_STARTS is all air, and
	 * its lazily-primed heightmap makes the probe return the dimension floor.
	 * A probed value this close to the floor is cross-checked against the noise
	 * height before being trusted (analysis report section 5, item 2).
	 */
	public static final int FLOOR_GARBAGE_MARGIN = 8;

	private HeightSanity() {
	}

	/**
	 * True when {@code realTopY} looks like a lazily-primed empty-chunk floor
	 * value and the noise height should be used instead. A {@code noiseTopY}
	 * of {@code Integer.MIN_VALUE} (see {@code VpsGenContext#NOISE_UNAVAILABLE})
	 * means "no noise height available" and never triggers distrust.
	 */
	public static boolean shouldDistrustRealHeight(int realTopY, int noiseTopY, int minY) {
		return noiseTopY != Integer.MIN_VALUE
				&& realTopY - minY <= FLOOR_GARBAGE_MARGIN
				&& noiseTopY > realTopY;
	}

	/**
	 * True when a probed surface sits exactly at (or below) the dimension
	 * floor: the precise signature of a lazily-primed all-air chunk. Genuinely
	 * low worlds such as superflat keep a few blocks of terrain above the
	 * floor and are not matched.
	 */
	public static boolean isExactFloorProbe(int surfaceTopY, int minY) {
		return surfaceTopY <= minY;
	}
}

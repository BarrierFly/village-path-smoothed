/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

/**
 * Pure decision helpers for the structure-beard compensation. No Minecraft
 * types, so unit tests can load this class without the game on the classpath.
 *
 * Background: vanilla adapts the noise terrain around RIGID jigsaw pieces
 * (village buildings) toward the piece base, but
 * {@code ChunkGenerator#getBaseHeight} evaluates the noise with a no-op
 * beardifier ({@code BeardifierMarker} returns 0.0), so the noise probe does
 * not see that adaptation at all. Two approximations live here:
 *
 * <ul>
 *   <li>{@link #compensate} - the legacy flat +1 block inside a Chebyshev
 *       radius ("step" kernel mode). Only raises, never lowers.</li>
 *   <li>{@link #beardThinDensity} / {@link #compensateBeardThin} - the real
 *       {@code beard_thin} closed form derived from vanilla
 *       {@code Beardifier#getBeardContribution} (review 3 section 2.4, P1-B):
 *       {@code -0.8 * (p + 0.5) / sqrt(2e) * exp(-e / 16)} with
 *       {@code e = m^2 + n^2 + (p + 0.5)^2}, mirrored about {@code p = -0.5}
 *       (cuts above the base plane, fills below). The density value still
 *       needs a blocks-per-density conversion factor that only an in-game
 *       calibration can pin down, so the caller supplies it and the default
 *       configuration keeps the legacy step mode until then.</li>
 * </ul>
 */
public final class BeardCompensation {
	/** Vanilla {@code Beardifier.BEARD_KERNEL_RADIUS}: the kernel's support. */
	private static final int KERNEL_RADIUS = 12;

	private BeardCompensation() {
	}

	/**
	 * True when the column sits within {@code radius} (Chebyshev distance) of
	 * a building's footprint. {@code boxes} rows are
	 * {@code {minX, maxX, minZ, maxZ}} of RIGID (bearded) piece bounding
	 * boxes; longer rows simply carry the vertical fields unused here.
	 */
	public static boolean isNearBuilding(int[][] boxes, int x, int z, int radius) {
		if (boxes == null || radius < 0) {
			return false;
		}
		for (int[] box : boxes) {
			int dx = Math.max(0, Math.max(box[0] - x, x - box[1]));
			int dz = Math.max(0, Math.max(box[2] - z, z - box[3]));
			if (dx <= radius && dz <= radius) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Raises a noise-probed surface by {@code amount} blocks for columns in
	 * the beard zone; never lowers anything (the step beard only fills).
	 */
	public static int compensate(int noiseTopY, boolean nearBuilding, int amount) {
		return nearBuilding && amount > 0 ? noiseTopY + amount : noiseTopY;
	}

	/**
	 * The vanilla {@code BEARD_THIN} beard density at one column, summed over
	 * all RIGID piece boxes. {@code boxes} rows are
	 * {@code {minX, maxX, minZ, maxZ, minY, groundLevelDelta}} so the vertical
	 * coordinate {@code p = y - (minY + groundLevelDelta)} matches
	 * {@code Beardifier#compute} exactly. Returns a value in vanilla DENSITY
	 * units: negative above the base plane (terrain is cut), positive below
	 * (terrain is filled), zero outside the 24x24x24 kernel.
	 */
	public static double beardThinDensity(int[][] boxes, int x, int y, int z) {
		if (boxes == null) {
			return 0.0;
		}
		double sum = 0.0;
		for (int[] box : boxes) {
			int m = Math.max(0, Math.max(box[0] - x, x - box[1]));
			int n = Math.max(0, Math.max(box[2] - z, z - box[3]));
			int p = y - (box[4] + box[5]);
			if (m >= KERNEL_RADIUS || n >= KERNEL_RADIUS || p < -KERNEL_RADIUS || p > KERNEL_RADIUS - 1) {
				continue; // outside the kernel support: contribution is exactly 0
			}
			double d = p + 0.5;
			double e = (double) m * m + (double) n * n + d * d;
			sum += -0.8 * d / Math.sqrt(2.0 * e) * Math.exp(-e / 16.0);
		}
		return sum;
	}

	/**
	 * Surface shift in whole blocks for the {@code beard_thin} kernel mode:
	 * the vanilla density contribution converted with the (calibrated) blocks
	 * per density factor and added to the noise probe. The factor is positive
	 * by definition: a negative density (above the base plane) lowers the
	 * surface, a positive one raises it, exactly like the real beardifier.
	 * Rounds half away from zero so the shift is deterministic.
	 */
	public static int compensateBeardThin(int noiseTopY, int[][] boxes, int x, int y, int z, double blocksPerDensity) {
		if (boxes == null || blocksPerDensity <= 0) {
			return noiseTopY;
		}
		double shift = beardThinDensity(boxes, x, y, z) * blocksPerDensity;
		if (shift == 0.0) {
			return noiseTopY;
		}
		return noiseTopY + (int) Math.round(shift);
	}
}

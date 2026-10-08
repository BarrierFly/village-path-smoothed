/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

/**
 * Pure decision helpers for the structure-beard compensation. No Minecraft
 * types, so unit tests can load this class without the game on the classpath.
 *
 * Background: vanilla raises (beards) the noise terrain around RIGID jigsaw
 * pieces (village buildings) toward the piece base, but
 * {@code ChunkGenerator#getBaseHeight} evaluates the noise with a no-op
 * beardifier ({@code BeardifierMarker} returns 0.0), so the noise probe
 * systematically UNDERESTIMATES the real surface next to buildings by about
 * one block - which used to sink road segments there by exactly that block
 * (too small a drop for a kerb: bare dirt walls).
 */
public final class BeardCompensation {
	private BeardCompensation() {
	}

	/**
	 * True when the column sits within {@code radius} (Chebyshev distance) of
	 * a building's footprint. {@code boxes} rows are
	 * {@code {minX, maxX, minZ, maxZ}} of RIGID (bearded) piece bounding
	 * boxes.
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
	 * the beard zone; never lowers anything (the beard only fills).
	 */
	public static int compensate(int noiseTopY, boolean nearBuilding, int amount) {
		return nearBuilding && amount > 0 ? noiseTopY + amount : noiseTopY;
	}
}

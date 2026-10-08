/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

/**
 * Pure decision helpers for the per-cell fill/clear caps. No Minecraft types,
 * so unit tests can load this class without the game on the classpath.
 */
public final class RoadWritePolicy {
	private RoadWritePolicy() {
	}

	/**
	 * True when the road sits more than {@code maxFillLayers} whole blocks
	 * above the ground and must become a bridge deck instead of stacking an
	 * unlimited landfill. A negative cap means unlimited (never bridge).
	 *
	 * @param fillLayers whole-block layers of fill between the ground surface and the road block
	 * @param maxFillLayers configured cap (roadSmoothing.maxFillLayers)
	 */
	public static boolean bridgeDeck(int fillLayers, int maxFillLayers) {
		return maxFillLayers >= 0 && fillLayers > maxFillLayers;
	}

	/**
	 * Inclusive top Y of the clear loop above the road block; the caller loops
	 * from {@code roadTopBlockY + 1} to this value, so a result below that
	 * start clears nothing. A negative cap means unlimited (clear up to the
	 * vanilla surface, the old behaviour).
	 *
	 * @param roadTopBlockY Y of the top road block
	 * @param surfaceTopY first air above the vanilla surface
	 * @param maxClearLayers configured cap (roadSmoothing.maxClearLayers)
	 */
	public static int clearTopY(int roadTopBlockY, int surfaceTopY, int maxClearLayers) {
		if (maxClearLayers < 0) {
			return surfaceTopY - 1;
		}
		return Math.min(surfaceTopY - 1, roadTopBlockY + Math.max(0, maxClearLayers));
	}

	/**
	 * True when the capped clear leaves terrain above the headroom: that
	 * terrain forms a tunnel roof and the writer lines it with the kerb
	 * material. Never true for the unlimited cap.
	 */
	public static boolean tunnelRoof(int roadTopBlockY, int surfaceTopY, int clearTop, int maxClearLayers) {
		return maxClearLayers >= 0
				&& clearTop < surfaceTopY - 1
				&& clearTop >= roadTopBlockY;
	}
}

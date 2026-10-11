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

	/**
	 * Road walk surface in half-block units: a whole road block tops out 1.0
	 * above its block Y, a bottom slab 0.5. All drop criteria are computed in
	 * this unit (A1) - never from {@code roadTopBlockY + 1}.
	 */
	public static int roadWalkSurface2(int roadTopBlockY, boolean slab) {
		return 2 * roadTopBlockY + (slab ? 1 : 2);
	}

	/** Kerb: the side walk surface is more than {@code minDropHeight} whole blocks above the road. */
	public static boolean kerbApplies(int sideWalkSurface2, int roadWalkSurface2, int minDropHeight) {
		return sideWalkSurface2 - roadWalkSurface2 > 2 * minDropHeight;
	}

	/** Railing: the road walk surface is more than {@code minDropHeight} whole blocks above the side. */
	public static boolean railingApplies(int sideWalkSurface2, int roadWalkSurface2, int minDropHeight) {
		return roadWalkSurface2 - sideWalkSurface2 > 2 * minDropHeight;
	}

	/**
	 * True when the solved walk surface {@code p} (half units) is beyond the
	 * affordable fill (road above the surface) or cut (road below) cap by more
	 * than 2 whole blocks; the writer then snaps the cell onto the real surface
	 * with the correct parity (A5). The relevant cap is chosen by direction and
	 * a negative cap means unlimited (never triggers).
	 */
	public static boolean overBudget(int p, int surfaceTopY, int maxFillLayers, int maxClearLayers) {
		int diff = p - 2 * surfaceTopY; // half units, positive = road above the surface (fill)
		int budget = diff >= 0 ? maxFillLayers : maxClearLayers;
		if (budget < 0) {
			return false;
		}
		return Math.abs(diff) > 2 * (budget + 2);
	}

	/** Fallback parity: odd P is a bottom slab, even P a whole block (both at surfaceTopY - 1). */
	public static boolean fallbackSlab(int p) {
		return (p & 1) != 0;
	}
}

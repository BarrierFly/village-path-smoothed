/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

/**
 * Pure water-crossing predicate shared by the road writer (scaffolding) and
 * the road collector (water anchors). No Minecraft types, so it is unit
 * testable.
 *
 * The old implementation only recognised a crossing when the vanilla road
 * block itself was a plank or water. The real criterion is fluid based: a
 * column is a crossing when there is water at the vanilla road block height
 * ({@code wt}) OR one block below it. Vanilla puts the plank at the water
 * surface, so the water can be at {@code wt} (the planks replace it) or at
 * {@code wt - 1} (the planks float on top of the water); reading only one of
 * the two mis-detects the other branch.
 */
public final class WaterCrossings {
	private WaterCrossings() {
	}

	/**
	 * True when this column is a water crossing.
	 *
	 * @param waterAtRoadBlock water occupies the vanilla road block height (wt)
	 * @param waterBelowRoadBlock water occupies wt - 1 (the plank floats on it)
	 */
	public static boolean isCrossing(boolean waterAtRoadBlock, boolean waterBelowRoadBlock) {
		return waterAtRoadBlock || waterBelowRoadBlock;
	}
}

/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.terrain;

import java.util.function.IntPredicate;

/**
 * Directional column probes. No Minecraft types, so unit tests can load this
 * class without the game on the classpath.
 *
 * <p>Every height decision the writer makes used to read one number: the
 * {@code WORLD_SURFACE_WG} heightmap top of the column. That is the highest
 * non-air block, so a tree canopy, a floating overhang or a carved cave ceiling
 * all shadow the real ground, and a road running through a multi-layer column
 * was filled or cut against the wrong surface. The two probes below look in the
 * one direction each decision actually needs:
 *
 * <ul>
 *   <li>{@link #downwardSurfaceTopY} scans <b>down</b> from the road block to
 *       the first standable block: that is the ground a fill/bridge rides on
 *       (and the surface a nudged cell snaps to);</li>
 *   <li>{@link #upwardSolidTopY} scans <b>up</b> from the road block to the top
 *       of the contiguous solid run: that is the ceiling a cut/tunnel has to
 *       clear.</li>
 * </ul>
 *
 * <p>Both return a "first air above the surface" Y, the same unit
 * {@code VpsGenContext#surfaceTopY} used, so the existing fill/clear helpers do
 * not have to change shape. The raw heightmap read stays in use for the
 * model-vs-terrain deviation statistics and the building floating/buried guard,
 * which are observations, not write decisions.
 */
public final class ColumnScan {
	private ColumnScan() {
	}

	/**
	 * First-air-above-surface Y found by scanning down from {@code fromY}
	 * (inclusive) to {@code minY} for the first block whose top face is
	 * standable. Returns {@code minY} when the whole column is empty, i.e. the
	 * ground block is one below the world.
	 *
	 * @param standable true when a block can carry a road block on top of it
	 * @param fromY the road block's own Y: a solid block there means "the road is
	 *        cut into the terrain", giving a surface at {@code fromY + 1} and
	 *        therefore no fill
	 */
	public static int downwardSurfaceTopY(IntPredicate standable, int fromY, int minY) {
		for (int y = fromY; y >= minY; y--) {
			if (standable.test(y)) {
				return y + 1;
			}
		}
		return minY;
	}

	/**
	 * Highest Y of the contiguous run of solid blocks starting at {@code fromY};
	 * {@code fromY - 1} when that block is not solid, so the "first air above the
	 * ceiling" is {@code fromY} and nothing gets cleared.
	 *
	 * @param solid true when a block is terrain to be cleared (not air, not a
	 *        fluid, not replaceable vegetation)
	 */
	public static int upwardSolidTopY(IntPredicate solid, int fromY, int maxY) {
		int top = fromY - 1;
		for (int y = fromY; y <= maxY && solid.test(y); y++) {
			top = y;
		}
		return top;
	}

	/** First-air-above-ceiling Y for {@link #upwardSolidTopY}. */
	public static int upwardSurfaceTopY(IntPredicate solid, int fromY, int maxY) {
		return upwardSolidTopY(solid, fromY, maxY) + 1;
	}
}

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
	/** Sentinel walk surface for a column the writer refused to write (fix plan v4 D6-b). */
	public static final int UNWRITTEN = Integer.MIN_VALUE;

	private RoadWritePolicy() {
	}

	/**
	 * What the writer actually puts in one column, in the same half-block walk
	 * surface units the solver uses ({@code S = 2 * walkSurface}).
	 *
	 * <p>Extracted as a pure function (fix plan v4 M0) so the bench measures the
	 * writer's real height decisions instead of mirroring them: the road profile
	 * can be legal in the mask and still be overwritten at write time by the
	 * water-crossing branch, the dimension clamp or the exception fallback
	 * (fix plan v4 F7). The bench calls these two methods for every cell, so a
	 * change to the writer's geometry shows up in {@code writtenViolEdg} without
	 * the test duplicating a rule.
	 *
	 * @param walkSurface2 the walk surface actually written, {@link #UNWRITTEN}
	 *        when the writer refused the cell
	 * @param slab whether the road block is a bottom slab
	 * @param written false when the writer refused to write the column
	 * @param overrodeSolved true when the written walk surface differs from the
	 *        cell's solved surface - i.e. the writer broke the solution
	 */
	public record Column(int walkSurface2, boolean slab, boolean written, boolean overrodeSolved) {
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
	 * than 2 whole blocks. Since fix plan v2 R3 this is an observation flag
	 * only: the caller counts the cell and renders the solved height as-is
	 * (bridge deck / tunnel per the caps); it no longer snaps anything. The
	 * relevant cap is chosen by direction and a negative cap means unlimited
	 * (never triggers).
	 */
	/**
	 * Height decision of the water-crossing branch (scaffolding instead of the
	 * vanilla planks).
	 *
	 * <p>Extracted from {@code RoadWriter#writeCell} verbatim in fix plan v4 M0:
	 * the scaffolding is pinned to the vanilla surface height
	 * ({@code realSurfaceTopY - 1}) and the cell's solved height is discarded.
	 * That is one of the three writer-side overrides (F7) and the bench measures
	 * it as {@code writeOverrideCells}; fix plan v4 D6-b (M5) makes the scaffold
	 * carry the solved height like every other column.
	 *
	 * @param solvedP the cell's solved walk surface (half units)
	 * @param realSurfaceTopY first air above the real terrain (writer context)
	 * @param crossingEnabled waterCrossing.enabled
	 * @param waterAtRoadBlock water at the vanilla road block height
	 * @param waterBelowRoadBlock water one block below it
	 */
	public static Column waterCrossingColumn(int solvedP, int realSurfaceTopY, boolean crossingEnabled,
			boolean waterAtRoadBlock, boolean waterBelowRoadBlock, int minY, int maxY) {
		if (!crossingEnabled || !dev.peoplecanfly.village_path_smoothed.terrain.WaterCrossings.isCrossing(waterAtRoadBlock, waterBelowRoadBlock)) {
			return profileColumn(solvedP, minY, maxY);
		}
		int written = roadWalkSurface2(realSurfaceTopY - 1, false);
		return new Column(written, false, true, written != solvedP);
	}

	/**
	 * Height decision of the profile branch: the road block sits where the
	 * solver put the walk surface, clamped into the dimension bounds. A clamped
	 * height is still written (fix plan v4 D6-b turns this into a refusal).
	 */
	public static Column profileColumn(int solvedP, int minY, int maxY) {
		int roadTopBlockY = roadTopBlockY(solvedP);
		boolean slab = (solvedP & 1) != 0;
		int clamped = clamp(minY, maxY, roadTopBlockY);
		int written = roadWalkSurface2(clamped, slab);
		return new Column(written, slab, true, clamped != roadTopBlockY);
	}

	/** Y of the top road block for a solved walk surface: whole block vs bottom slab. */
	public static int roadTopBlockY(int solvedP) {
		return (solvedP & 1) == 0 ? solvedP / 2 - 1 : (solvedP - 1) / 2;
	}

	private static int clamp(int minY, int maxY, int y) {
		return Math.max(minY, Math.min(maxY, y));
	}

	public static boolean overBudget(int p, int surfaceTopY, int maxFillLayers, int maxClearLayers) {
		int diff = p - 2 * surfaceTopY; // half units, positive = road above the surface (fill)
		int budget = diff >= 0 ? maxFillLayers : maxClearLayers;
		if (budget < 0) {
			return false;
		}
		return Math.abs(diff) > 2 * (budget + 2);
	}
}

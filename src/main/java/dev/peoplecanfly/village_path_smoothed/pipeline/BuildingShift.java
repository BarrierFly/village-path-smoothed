/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.Arrays;

/**
 * Pure decision helper for the T1.5 building shift (review 3 T5). No Minecraft
 * types, so unit tests can load this class without the game on the classpath.
 *
 * <p>Heights are half-block walk-surface units (P = 2 * walkSurfaceY), the
 * shift is in whole blocks.
 *
 * <p>Fix plan analysis 2026-10-15 (section 3.1/3.2, plan D): the alignment
 * target is the component's <b>door</b> road cells and the alignment reference
 * is the top of the structure's <b>Y0 template layer</b> ({@code baseY + 1} -
 * the y=0 layer is the floor/基座 block; 75% of vanilla door blocks sit at y=1,
 * right on that surface). Aligning {@code baseY} itself left every door one
 * whole block above its road (the structural part of the measured +1.5 door
 * offsets). And the "single median" is replaced by the <b>legal interval</b>:
 * a shift is legal when the shifted Y0 top stays within half a block (1 half
 * unit) of EVERY door road cell's solved surface, and inside the interval the
 * shift closest to zero wins (the building moves as little as vanilla
 * placement allows). The median is only the fallback for a door whose road
 * cells span more than the tolerance can bridge.
 *
 * <p>Plan E (user decision 2026-10-15, after the door two-symptom recheck):
 * the anchor is the template's <b>real door block</b>, not the connection
 * junction (the junction sits 2-7 blocks from the door on 53 of the 120
 * vanilla house templates), and the reference is the <b>Y0 layer</b>, not the
 * door layer - vanilla designs the foundation to face the road, so a template
 * whose door sits higher (doorY &gt; 1, e.g. the stilt fisher cottage) keeps
 * its door above the road on purpose. Every door road cell presents its
 * <b>solid level</b> to the house ({@link #solidTargetP}): a full block its
 * own top, a bottom slab counts as air and presents the level below it, so
 * the house's Y0 layer sits flush with a full-block road and half a block
 * below a slab road. All targets are therefore whole-block levels: the
 * interval is either a single exact value or empty (median fallback), and the
 * odd-parity fallback flag can no longer fire.
 */
public final class BuildingShift {
	/** Sentinel shift: the component must not be moved. */
	public static final int DO_NOT_MOVE = Integer.MIN_VALUE;

	/**
	 * The y=0 template layer is the foundation; its top walk surface (the
	 * interior floor) is one block above it. Plan E (2026-10-15): the alignment
	 * follows the Y0 LAYER, not the door - vanilla houses are designed with
	 * their bottom layer facing the road.
	 */
	public static final int DOOR_WALK_OFFSET = 1;

	/** Why a component is or is not served by the adjacent road profile. */
	public enum Reason {
		SERVED, NO_ROAD
	}

	/**
	 * @param shift whole blocks; {@link #DO_NOT_MOVE} unless {@code reason ==
	 *        SERVED}
	 * @param doorParityOdd true when the FALLBACK median walk surface is a slab
	 *        level (odd P) and the median scheme was used; an interval pick is
	 *        always whole-block-consistent, so it never flags (B3-4)
	 * @param viaInterval true when the shift came from the legal interval (the
	 *        road cells fit the half-block tolerance around one door floor);
	 *        false means the median fallback decided
	 * @param medianShift the shift the legacy single-median rule would have
	 *        produced; the optional two-wing limit clamps around it
	 */
	public record ShiftDecision(Reason reason, int shift, boolean doorParityOdd, boolean viaInterval, int medianShift) {
		private static final ShiftDecision DO_NOT_MOVE_DECISION =
				new ShiftDecision(Reason.NO_ROAD, DO_NOT_MOVE, false, false, DO_NOT_MOVE);

		static ShiftDecision doNotMove() {
			return DO_NOT_MOVE_DECISION;
		}
	}

	private BuildingShift() {
	}

	/**
	 * The solid level one road cell presents to a building (plan E, user
	 * decision 2026-10-15): a full road block presents its own top walk
	 * surface; a bottom slab counts as AIR and presents the walk surface of the
	 * block below it - aligning to it puts the house's Y0 layer half a block
	 * below the slab's walk surface ("房屋底层比半砖路低半格").
	 */
	public static int solidTargetP(int solvedP) {
		return solvedP - (solvedP & 1);
	}

	/**
	 * Shift so the component's Y0 layer top (the interior floor) meets the
	 * solid levels of the road cells at its door, staying within half a block
	 * of each of them when possible.
	 *
	 * @param adjacentRoadP solved walk surfaces (half units) of the road cells
	 *        at the component's door, selected by the caller; each is converted
	 *        with {@link #solidTargetP} before use (slab cells count as air)
	 * @param baseY the component's base layer Y (the median of its pieces'
	 *        bounding-box minY; the template's y=0 floor layer)
	 */
	public static ShiftDecision desiredShift(int[] adjacentRoadP, int baseY) {
		if (adjacentRoadP == null || adjacentRoadP.length == 0) {
			return ShiftDecision.doNotMove();
		}
		int doorWalk = baseY + DOOR_WALK_OFFSET;
		int[] targets = new int[adjacentRoadP.length];
		for (int i = 0; i < targets.length; i++) {
			targets[i] = solidTargetP(adjacentRoadP[i]);
		}

		// legacy reference for the optional two-wing limit: the median scheme
		int medianHalf = median(targets);
		int medianShift = (int) Math.round(medianHalf / 2.0 - doorWalk);

		// legal interval: |2*(doorWalk + dy) - P_i| <= 1 for every door road cell
		int lo = Integer.MIN_VALUE;
		int hi = Integer.MAX_VALUE;
		for (int p : targets) {
			// (p - 1) / 2 rounded up, (p + 1) / 2 rounded down, both minus doorWalk
			int cellLo = ceilHalf(p - 1) - doorWalk;
			int cellHi = floorHalf(p + 1) - doorWalk;
			lo = Math.max(lo, cellLo);
			hi = Math.min(hi, cellHi);
		}
		if (lo <= hi) {
			// inside the interval the shift closest to zero wins: the building
			// moves as little as the door alignment allows. With whole-block
			// targets (plan E) every cell forces the same parity, so the interval
			// is a single exact value - or empty, below.
			int closestToZero = Math.max(lo, Math.min(hi, 0));
			return new ShiftDecision(Reason.SERVED, closestToZero, false, true, medianShift);
		}

		// the door road cells span more than the tolerance can bridge: fall back
		// to the legacy median scheme (user decision 2026-10-15, plan D). The
		// median of whole-block targets is whole-block, so the parity flag
		// cannot fire any more (plan E).
		boolean oddParity = (medianHalf & 1) != 0;
		return new ShiftDecision(Reason.SERVED, medianShift, oddParity, false, medianShift);
	}

	/** ceil(n / 2) for any int (positive or negative). */
	private static int ceilHalf(int n) {
		return (n + 1) >> 1;
	}

	/** floor(n / 2) for any int (positive or negative). */
	private static int floorHalf(int n) {
		return n >> 1;
	}

	private static int median(int[] values) {
		int[] copy = Arrays.copyOf(values, values.length);
		Arrays.sort(copy);
		return copy[copy.length / 2];
	}
}

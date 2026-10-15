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
 * target is the component's <b>door interior walk surface</b>, {@code baseY + 1}
 * - the y=0 template layer is the floor/基座 block and the door opens at y=1,
 * so aligning {@code baseY} itself left every door one whole block above its
 * road (the structural part of the measured +1.5 door offsets). And the
 * "single median" is replaced by the <b>legal interval</b>: a shift is legal
 * when the shifted door interior floor stays within half a block (1 half unit)
 * of EVERY door-adjacent road cell's solved surface, and inside the interval
 * the shift closest to zero wins (the building moves as little as vanilla
 * placement allows). The median is only the fallback for a door whose road
 * cells span more than the tolerance can bridge.
 */
public final class BuildingShift {
	/** Sentinel shift: the component must not be moved. */
	public static final int DO_NOT_MOVE = Integer.MIN_VALUE;

	/**
	 * The y=0 template layer is the floor; the walkable surface inside the door
	 * is one block above it (measured on 6 vanilla templates, 2026-10-15).
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
	 * Shift so the component's door interior floor meets the road cells at its
	 * door, staying within half a block of each of them when possible.
	 *
	 * @param adjacentRoadP solved walk surfaces (half units) of the road cells
	 *        at the component's door, selected by the caller
	 * @param baseY the component's base layer Y (the median of its pieces'
	 *        bounding-box minY; the template's y=0 floor layer)
	 */
	public static ShiftDecision desiredShift(int[] adjacentRoadP, int baseY) {
		if (adjacentRoadP == null || adjacentRoadP.length == 0) {
			return ShiftDecision.doNotMove();
		}
		int doorWalk = baseY + DOOR_WALK_OFFSET;

		// legacy reference for the optional two-wing limit: the median scheme
		int medianHalf = median(adjacentRoadP);
		int medianShift = (int) Math.round(medianHalf / 2.0 - doorWalk);

		// legal interval: |2*(doorWalk + dy) - P_i| <= 1 for every door road cell
		int lo = Integer.MIN_VALUE;
		int hi = Integer.MAX_VALUE;
		for (int p : adjacentRoadP) {
			// (p - 1) / 2 rounded up, (p + 1) / 2 rounded down, both minus doorWalk
			int cellLo = ceilHalf(p - 1) - doorWalk;
			int cellHi = floorHalf(p + 1) - doorWalk;
			lo = Math.max(lo, cellLo);
			hi = Math.min(hi, cellHi);
		}
		if (lo <= hi) {
			// inside the interval the shift closest to zero wins: the building
			// moves as little as the door alignment allows
			int closestToZero = Math.max(lo, Math.min(hi, 0));
			return new ShiftDecision(Reason.SERVED, closestToZero, false, true, medianShift);
		}

		// the door road cells span more than the tolerance can bridge: fall back
		// to the legacy median scheme (user decision 2026-10-15, plan D)
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

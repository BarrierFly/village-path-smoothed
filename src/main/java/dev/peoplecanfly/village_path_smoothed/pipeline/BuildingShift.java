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
 * <p>Extracted verbatim from {@code T15Planner#desiredShift}: the vertical
 * shift so a building component's base meets the median adjacent road walk
 * surface. Heights are half-block walk-surface units (P = 2 * walkSurfaceY),
 * the shift is in whole blocks.
 */
public final class BuildingShift {
	/** Sentinel shift: the component must not be moved. */
	public static final int DO_NOT_MOVE = Integer.MIN_VALUE;

	/** Why a component is or is not served by the adjacent road profile. */
	public enum Reason {
		SERVED, NO_ROAD
	}

	/**
	 * @param shift whole blocks; {@link #DO_NOT_MOVE} unless {@code reason ==
	 *        SERVED}
	 * @param doorParityOdd true when the serving median walk surface is a slab
	 *        level (odd P); the optional B3-3 third solve pass that would force
	 *        an even door cell is not implemented, so the caller counts this as
	 *        a parity fallback (B3-4)
	 */
	public record ShiftDecision(Reason reason, int shift, boolean doorParityOdd) {
	}

	private BuildingShift() {
	}

	/**
	 * Shift so the component base meets the median of the road cells at its door
	 * (its immediately adjacent road, selected by the caller).
	 *
	 * <p>The old {@code SPAN_TOO_WIDE} refusal (&quot;adjacent road cells span
	 * more than 3 blocks, so the building cannot be served&quot;) is gone (user
	 * decision 2026-10-14): it left the building at the vanilla terrain height
	 * next to a smoothed road, which is the symptom it was supposed to avoid.
	 * The road profile is 1-Lipschitz, so any single adjacent cell is a legal
	 * target; the median is the best single choice for the door.
	 */
	public static ShiftDecision desiredShift(int[] adjacentRoadP, int baseY) {
		if (adjacentRoadP == null || adjacentRoadP.length == 0) {
			return new ShiftDecision(Reason.NO_ROAD, DO_NOT_MOVE, false);
		}
		int medianHalf = median(adjacentRoadP);
		boolean oddParity = (medianHalf & 1) != 0;
		double targetBlocks = medianHalf / 2.0;
		return new ShiftDecision(Reason.SERVED, (int) Math.round(targetBlocks - baseY), oddParity);
	}

	private static int median(int[] values) {
		int[] copy = Arrays.copyOf(values, values.length);
		Arrays.sort(copy);
		return copy[copy.length / 2];
	}
}

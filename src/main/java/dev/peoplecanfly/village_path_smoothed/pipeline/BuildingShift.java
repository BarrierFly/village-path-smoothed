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
 * <p>Door/basis fix plan 2026-10-17 (D1/D2): the alignment target is the
 * component's <b>main-door</b> road cell and the reference is
 * {@code baseWalkY = mainPiece.bbox.minY + getGroundLevelDelta()} - the exact
 * level vanilla's terrain adaptation aligns the piece to. The old
 * {@code median minY + 1} approximation (and the {@code DOOR_WALK_OFFSET=1}
 * constant it needed) is gone. The "single median" is still replaced by the
 * <b>legal interval</b>: a shift is legal when the shifted base walk level
 * stays within half a block (1 half unit) of EVERY selected door road cell's
 * solid surface, and inside the interval the shift closest to zero wins. The
 * median is only the fallback when the targets span more than the tolerance can
 * bridge.
 *
 * <p>Plan E (user decision 2026-10-15, after the door two-symptom recheck):
 * the anchor is the template's <b>real door block</b>, not the connection
 * junction (the junction sits 2-7 blocks from the door on 53 of the 120
 * vanilla house templates). Every door road cell presents its <b>solid
 * level</b> to the house ({@link #solidTargetP}): a full block its own top, a
 * bottom slab counts as air and presents the level below it, so the house's
 * base walk level sits flush with a full-block road and half a block below a
 * slab road. All targets are therefore whole-block levels: the interval is
 * either a single exact value or empty (median fallback), and the odd-parity
 * fallback flag can no longer fire.
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
	 *        at the component's main door, selected by the caller; each is
	 *        converted with {@link #solidTargetP} before use (slab cells count
	 *        as air)
	 * @param baseWalkY the component's base walk level - the main building
	 *        piece's {@code bbox.minY + getGroundLevelDelta()}, the exact level
	 *        vanilla's terrain adaptation aligns the piece to (door/basis fix
	 *        plan 2026-10-17, D1). It replaces the old {@code baseY + 1}
	 *        approximation; no offset is added here.
	 */
	public static ShiftDecision desiredShift(int[] adjacentRoadP, int baseWalkY) {
		if (adjacentRoadP == null || adjacentRoadP.length == 0) {
			return ShiftDecision.doNotMove();
		}
		int doorWalk = baseWalkY;
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

	/**
	 * Plan 2026-10-17 (door/basis fix, section 3.5): the second authoritative
	 * alignment works from a TOTAL shift from the vanilla position, while the
	 * component has already accumulated its first-pass {@code dy}. The
	 * additional move is the difference; {@link #DO_NOT_MOVE} means "no
	 * authoritative target" and must never undo the first pass (user decision
	 * 2026-10-17: keep the current shift in that case).
	 */
	public static int additionalShift(int desiredTotal, int currentDy) {
		return desiredTotal == DO_NOT_MOVE ? 0 : desiredTotal - currentDy;
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

	/**
	 * R2-A (four-symptom fix plan 2026-10-16): the baseY of a rigid component,
	 * with 1x1 pieces excluded from the median. A 1x1 decor piece (a light or a
	 * decoration hung on the house's up jigsaw) sits one or two blocks above the
	 * house; with the old upper-median of every piece a "house + decor" 2-piece
	 * component took the decor's minY as its base, so {@code doorWalk = baseY + 1}
	 * landed one or two blocks above the real door floor and the house aligned
	 * too low. Only pieces with a footprint area greater than one count; when
	 * every piece is 1x1 (vanilla has no such building) the whole set is used as
	 * the fallback.
	 *
	 * @param minYs the component pieces' bounding-box minY
	 * @param oneByOne true where the piece's footprint is a single column
	 */
	public static int componentBaseY(int[] minYs, boolean[] oneByOne) {
		int[] big = new int[minYs.length];
		int n = 0;
		for (int i = 0; i < minYs.length; i++) {
			if (!oneByOne[i]) {
				big[n++] = minYs[i];
			}
		}
		if (n == 0) {
			big = Arrays.copyOf(minYs, minYs.length);
			n = minYs.length;
		} else {
			big = Arrays.copyOf(big, n);
		}
		Arrays.sort(big);
		return big[n / 2];
	}
}

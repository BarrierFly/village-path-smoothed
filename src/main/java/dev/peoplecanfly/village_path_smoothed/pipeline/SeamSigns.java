/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Debug seam signs (user request 2026-10-16): one standing sign along every
 * seam of the solved road mask, so the solver's half-block guarantee can be
 * verified cell by cell. A seam is a 4-adjacency edge whose two cells come from
 * DIFFERENT sources:
 *
 * <ul>
 *   <li>two different {@code street/terminator} pieces ({@code pieceIndex}
 *       differs);</li>
 *   <li>a plaza (town_centers) cell next to a non-plaza cell;</li>
 *   <li>a decor-seat cell next to a non-seat cell.</li>
 * </ul>
 *
 * <p>The solver's continuity promise is {@code |dP| <= 1} half units (0.5
 * blocks) on <b>every</b> edge, so two cells across a seam must differ by at
 * most 0.5 - a larger difference is a residual with a cause. The sign prints
 * line 1 the anchor cell "x y z", line 2 the far cell "x y z" (Y = solvedP / 2,
 * x.0 or x.5) and, when {@code |dP| >= 2}, lines 3/4 the known cause and its
 * magnitude. The sign is anchored on the "more structural" cell of the seam
 * (plaza &gt; seat &gt; normal, then the lower packedXZ); a multi-cell seam gives
 * one sign per structural cell. The far cell is the differing-kind neighbour
 * with the largest {@code |dP|} (ties keep the lowest x/z), i.e. the worst spot
 * of that seam cell.
 *
 * <p>Pure data, no Minecraft imports, so the rule is unit tested without the
 * game on the classpath; the writer places the blocks.
 */
public final class SeamSigns {
	public static final int REASON_NONE = 0;
	/** The water surface is a hard floor for a crossing cell. */
	public static final int REASON_WATER = 1;
	/** A decor seat is constrained to even walk surfaces. */
	public static final int REASON_SEAT = 2;
	/** A plaza group is pinned flat - a hard seam the road must meet. */
	public static final int REASON_PLAZA = 3;
	/** No-cut mode treats the terrain model ({@code baseP}) as a hard floor. */
	public static final int REASON_NO_CUT = 4;
	/** The polish pass could not make the edge legal without moving a pinned cell. */
	public static final int REASON_POLISH = 5;
	/** A seat's decor piece could not reach its level inside the world bounds. */
	public static final int REASON_WORLD_EDGE = 6;

	private static final int[] NX = {-1, 1, 0, 0};
	private static final int[] NZ = {0, 0, -1, 1};

	private SeamSigns() {
	}

	/**
	 * @param allowCutIntoTerrain the solve's {@code roadSmoothing.allowCutIntoTerrain};
	 *        when false a cell's {@code baseP} is a hard floor, which is one of
	 *        the named causes
	 * @return one entry per seam cell {anchorX, anchorSolvedP, anchorZ,
	 *         farX, farSolvedP, farZ, reason}
	 */
	public static int[][] compute(RoadNetwork net, boolean allowCutIntoTerrain) {
		// anchor cell index -> worst offending neighbour index, deduped so a cell
		// with several seam edges gets exactly one sign
		Map<Integer, Integer> anchorToNeighbour = new HashMap<>();
		int n = net.cells.size();
		for (int i = 0; i < n; i++) {
			RoadCell a = net.cells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j < 0) {
					continue;
				}
				RoadCell b = net.cells.get(j);
				if (!differentSource(a, b)) {
					continue;
				}
				int anchor = anchorIndex(a, b, i, j);
				int neighbour = anchor == i ? j : i;
				Integer current = anchorToNeighbour.get(anchor);
				if (current == null || worseSeam(net, anchor, neighbour, current)) {
					anchorToNeighbour.put(anchor, neighbour);
				}
			}
		}
		// deterministic order: anchors by cell index (packedXZ order)
		TreeMap<Integer, Integer> sorted = new TreeMap<>(anchorToNeighbour);
		int[][] out = new int[sorted.size()][];
		int k = 0;
		for (Map.Entry<Integer, Integer> entry : sorted.entrySet()) {
			RoadCell anchor = net.cells.get(entry.getKey());
			RoadCell far = net.cells.get(entry.getValue());
			out[k++] = new int[]{
					anchor.x, anchor.solvedP, anchor.z,
					far.x, far.solvedP, far.z,
					reason(anchor, far, allowCutIntoTerrain)
			};
		}
		return out;
	}

	/** Number of entries whose seam is not legal (reason != NONE). */
	public static int violationCount(int[][] signs) {
		int count = 0;
		for (int[] sign : signs) {
			if (sign[6] != REASON_NONE) {
				count++;
			}
		}
		return count;
	}

	/** Human-readable cause for a reason code (the writer prints it on line 3). */
	public static String reasonName(int reason) {
		switch (reason) {
			case REASON_WATER:
				return "WATER FLOOR";
			case REASON_SEAT:
				return "SEAT PARITY";
			case REASON_PLAZA:
				return "PLAZA PIN";
			case REASON_NO_CUT:
				return "NO-CUT FLOOR";
			case REASON_POLISH:
				return "POLISH RESID";
			case REASON_WORLD_EDGE:
				return "WORLD EDGE";
			default:
				return "";
		}
	}

	/**
	 * True when the candidate neighbour has a larger |dP| against the anchor than
	 * the current best. Cells are visited in packedXZ order and this is a strict
	 * beat, so ties keep the lowest x/z.
	 */
	private static boolean worseSeam(RoadNetwork net, int anchor, int candidate, int current) {
		int anchorP = net.cells.get(anchor).solvedP;
		int dc = Math.abs(net.cells.get(candidate).solvedP - anchorP);
		int dcur = Math.abs(net.cells.get(current).solvedP - anchorP);
		return dc > dcur;
	}

	private static boolean differentSource(RoadCell a, RoadCell b) {
		return a.plaza != b.plaza
				|| a.seat != b.seat
				|| a.pieceIndex != b.pieceIndex;
	}

	/** The "more structural" cell is line 1; plaza > seat > normal, ties by the lower packedXZ. */
	private static int anchorIndex(RoadCell a, RoadCell b, int i, int j) {
		int ra = structuralRank(a);
		int rb = structuralRank(b);
		if (ra != rb) {
			return ra > rb ? i : j;
		}
		long pa = RoadCell.packedXZ(a.x, a.z);
		long pb = RoadCell.packedXZ(b.x, b.z);
		return pa <= pb ? i : j;
	}

	private static int structuralRank(RoadCell cell) {
		if (cell.plaza) {
			return 2;
		}
		if (cell.seat) {
			return 1;
		}
		return 0;
	}

	private static int reason(RoadCell a, RoadCell b, boolean allowCutIntoTerrain) {
		if (Math.abs(a.solvedP - b.solvedP) <= 1) {
			return REASON_NONE;
		}
		if (a.plaza || b.plaza) {
			return REASON_PLAZA;
		}
		if (a.seat || b.seat) {
			return REASON_SEAT;
		}
		if (a.anchor || b.anchor) {
			return REASON_WATER;
		}
		if (a.edgeClamped || b.edgeClamped) {
			return REASON_WORLD_EDGE;
		}
		if (!allowCutIntoTerrain && (a.solvedP == a.baseP || b.solvedP == b.baseP)) {
			return REASON_NO_CUT;
		}
		return REASON_POLISH;
	}
}

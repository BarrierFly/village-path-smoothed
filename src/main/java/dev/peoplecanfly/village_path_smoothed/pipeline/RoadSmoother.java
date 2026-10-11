/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.Objective;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profile solver. Pure Java, no Minecraft imports.
 *
 * Heights are integers in half-block units (P = 2 * walk surface). The
 * constraint between 4-adjacent road cells is |dP| <= 1, enforced as a soft
 * penalty (stepViolationPenalty per half-block above 1) so cliffs stay solvable.
 *
 * Arms (thin strips) are solved by an exact slice DP: candidate values per cell
 * are windowed around the vanilla walk surface; states per slice are enumerated
 * and consecutive slices are linked by a separable min-plus transform. The
 * kernel factorizes because, with coordinate-swept slices, cross-slice edges
 * always connect cells that share the non-sweep coordinate. Wide regions are
 * seeded with a per-row/column gradient between the solved arm heights and then
 * re-optimized by iterative coordinate minimization (row/column runs
 * re-optimized with hard bounds from fixed orthogonal neighbours) followed by a
 * hard pass that guarantees every 4-adjacency constraint is exactly satisfied.
 *
 * <p>Review 4: the objective now measures deviation against the reference
 * profile {@link RoadReference} instead of raw terrain, and SMOOTH_FIRST ranks
 * deviation <em>above</em> the total step count. Review 3 had put steps on top,
 * which made "levelling a 1-block mound" strictly cheaper than "following it"
 * (measured: 0 steps / 24 half units of deviation against 12 steps / 6), and
 * equally made "plunging into a canyon" cheaper than "bridging it". Swapping
 * the two ranks alone would only have relocated the canyon failure; the
 * reference profile is what tells the two feature scales apart, and the
 * candidate bands are built around its target so a span's level is reachable at
 * all in the first place.
 *
 * <p>Review 4 also rebuilt the candidate bands. They used to be generated from
 * a chase line over raw terrain, which is why a bridge solution was not in the
 * candidate set for a wide canyon, and why a seam at <em>both</em> ends of an
 * arm degenerated into "merge the whole hard interval" and tore the junction
 * apart (probe M2). They are now generated from the reference target, and the
 * seam extension is a per-slice legal-slope cone from the nearest pinned slice.
 *
 * <p>Review 3: when an arm is re-solved against a region (phase 3), its
 * candidate band is extended toward the seam hard bound along the legal-slope
 * cone, so the seam can always be satisfied and an outlier direction becomes a
 * body deviation (the road ramps or sinks smoothly) instead of a full-height
 * seam cliff. Contradictions that survive the extension and every residual
 * |dP|>1 edge left after the whole solve are counted in the returned
 * {@link SolveStats} instead of staying silent.
 */
public final class RoadSmoother {
	/**
	 * Upper bound on enumerated states per slice before the DP window shrinks.
	 *
	 * <p>This is the single largest cost knob in the mod. Every slice of every
	 * arm pays O(states * values) for the forward transform and again for the
	 * backtrack, and both scale with the budget raised to the slice width, so a
	 * village pays it hundreds of times. At the old value of 30_000 a 3-wide
	 * street enumerates 31^3 = 29_791 states per slice - tens of millions of
	 * operations per slice once the transform and the backtrack are counted -
	 * which is what made village generation look like a hang rather than a
	 * slowdown.
	 *
	 * <p>25^3 gives a 3-wide slice 15_625 states, a 1.9x cut against the old
	 * budget, and the strided walk in {@link #transformMinPlus} takes another
	 * ~2.7x off what is left (see there). Going lower is not free, and the
	 * binding constraint is measured, not guessed: {@code RoadSmootherTest}
	 * probe M2 - a street clamped between two junction regions across a terrace
	 * wall - needs a candidate band at least 19 half units wide to reconcile the
	 * two seams, and fails with violating edges at 18 (budget 6_000) while
	 * passing at 20 (budget 8_000). Sitting one unit above that boundary would
	 * buy a further 2x at the cost of a configuration the review-4 design is
	 * specifically built around, so 25 is kept instead: six units of headroom,
	 * still a 1.9x state cut. Widen it back towards 31_000 only together with
	 * evidence that the M2 topology no longer occurs.
	 */
	private static final int STATE_BUDGET = 15_625;

public record SolveParams(
		Objective objective,
		int hybridWeightScaled,
		int stepViolationPenalty,
		int windowHalf,
		int icmRounds,
		boolean allowCut,
		int fillBudget2,
		int clearBudget2
) {
	public SolveParams(Objective objective, int hybridWeightScaled, int stepViolationPenalty, int windowHalf,
			int icmRounds, boolean allowCut) {
		this(objective, hybridWeightScaled, stepViolationPenalty, windowHalf, icmRounds, allowCut, -1, -1);
	}
}

	/**
	 * Residual diagnostics of one solve (review 3 P0-A). Everything reported
	 * here used to be a silent failure: candidate contradictions were resolved
	 * by quietly keeping the first-pass value, and leftover constraint
	 * violations were never looked at again.
	 *
	 * @param candidateContradictions cells whose hard bounds and candidate band
	 *        still disagreed after the seam extension, so the first-pass value
	 *        was kept as the only candidate
	 * @param residualViolations 4-adjacency edges with |dP| > 1 after the final
	 *        pass over the whole network
	 * @param worstStep the largest |dP| observed on any edge
	 * @param spanSlices slices whose reference is a level span (canyon, terrace);
	 *        review 4 section 5.3 - these are the cells the writer turns into
	 *        bridge decks or lined tunnels
	 */
	public record SolveStats(int candidateContradictions, int residualViolations, int worstStep, int spanSlices) {
		public static final SolveStats EMPTY = new SolveStats(0, 0, 0, 0);
	}

	/** Mutable per-solve accumulator threaded through the leaf candidate builders. */
	private static final class Stats {
		int candidateContradictions;
		int residualViolations;
		int worstStep;
		int spanSlices;

		SolveStats toStats() {
			return new SolveStats(candidateContradictions, residualViolations, worstStep, spanSlices);
		}
	}

	private RoadSmoother() {
	}

	public static SolveStats solve(RoadNetwork net, SolveParams p) {
		for (RoadCell cell : net.cells) {
			cell.solvedP = cell.baseP;
		}
		BitSet regionCells = new BitSet(net.cells.size());
		for (int[] region : net.regions) {
			for (int cell : region) {
				regionCells.set(cell);
			}
		}
		Stats stats = new Stats();

		// 0. references (review 4 section 5.3). Every arm gets its reference up
		//    front, before any band is built, because a fallback arm keeps this
		//    reference when it drops into the region solver - that is what stops
		//    the ICM from re-flattening a legal ramp back against raw terrain.
		for (RoadNetwork.Arm arm : net.arms) {
			RoadReference.Profile profile = armReference(net, arm, null, p.fillBudget2(), p.clearBudget2());
			for (int s = 0; s < arm.slices.size(); s++) {
				for (int cellIdx : arm.slices.get(s)) {
					net.cells.get(cellIdx).refP = profile.ref()[s];
				}
			}
			stats.spanSlices += profile.spanSlices();
		}

		// 1. arms with free boundaries; arms whose chase band is too wide for the
		//    exact DP fall back to the region solver (constraints stay exact)
		List<int[]> icmFallback = new ArrayList<>();
		for (RoadNetwork.Arm arm : net.arms) {
			if (!solveArm(net, arm, p, null, stats)) {
				icmFallback.add(armCells(arm));
			}
		}
		// 2. regions (gradient init from arm boundaries + ICM). The init walks
		//    must treat every ICM-solved cell as inside, so the membership set
		//    is regions plus fallback arms; otherwise a fallback arm's own cells
		//    look like "outside references" and the interpolation mixes raw
		//    baseP values across terrain cliffs into a garbage seed.
		BitSet icmMembership = (BitSet) regionCells.clone();
		for (int[] fallback : icmFallback) {
			for (int cell : fallback) {
				icmMembership.set(cell);
			}
		}
		for (int[] region : net.regions) {
			solveRegion(net, region, p, icmMembership, stats);
		}
		for (int[] fallback : icmFallback) {
			solveRegion(net, fallback, p, icmMembership, stats);
		}
		// 3. arms glued to a region: re-solve with the seam clipped to the fixed
		//    region heights, making the arm/region transition exact. An arm whose
		//    extended band still does not fit the state budget keeps its phase-1
		//    solution here; the final verification below counts the consequences.
		for (RoadNetwork.Arm arm : net.arms) {
			if (arm.touchesRegion) {
				solveArm(net, arm, p, seamBoundsForArm(net, arm, regionCells), stats);
			}
		}

		// global final verification (review 3 P0-A item 3): walk every 4-adjacency
		// edge of the finished network so no violation can stay invisible
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j <= i) {
					continue;
				}
				int d = Math.abs(net.cells.get(i).solvedP - net.cells.get(j).solvedP);
				if (d > 1) {
					stats.residualViolations++;
					stats.worstStep = Math.max(stats.worstStep, d);
				}
			}
		}
		return stats.toStats();
	}

	// ------------------------------------------------------------------
	// arm exact DP
	// ------------------------------------------------------------------

	/** Hard candidate bounds for arm cells that touch a region: |P - regionNeighbour| <= 1. */
	private static int[][] seamBoundsForArm(RoadNetwork net, RoadNetwork.Arm arm, BitSet regionCells) {
		int[][] bounds = new int[net.cells.size()][];
		for (int[] slice : arm.slices) {
			for (int cellIdx : slice) {
				int lo = Integer.MIN_VALUE;
				int hi = Integer.MAX_VALUE;
				for (int dir = 0; dir < 4; dir++) {
					int j = net.neighbors[cellIdx][dir];
					if (j >= 0 && regionCells.get(j)) {
						int v = net.cells.get(j).solvedP;
						lo = Math.max(lo, v - 1);
						hi = Math.min(hi, v + 1);
					}
				}
				if (lo != Integer.MIN_VALUE || hi != Integer.MAX_VALUE) {
					bounds[cellIdx] = new int[]{lo, hi};
				}
			}
		}
		return bounds;
	}

	/**
	 * Seam geometry of one arm in phase 3 (review 4, superseding review 3
	 * P0-A item 1).
	 *
	 * <p>Review 3 hulled <em>all</em> seam cells of the arm into one
	 * {@code [hardLo, hardHi]} interval. That is correct for the single
	 * outlier direction review 3 was about and wrong for the topology a real
	 * village actually has: an arm pinned at <em>both</em> ends. There the hull
	 * straddles the whole body, the extension degenerated into "merge the entire
	 * interval", the band lost every slope structure, and the arm collapsed to a
	 * low flat line that tore the far junction open (probe M2: 13 violating
	 * edges, worst 15 half units).
	 *
	 * <p>So the cone is now kept per slice: {@code lo[s]}/{@code hi[s]} is the
	 * range reachable from the nearest pinned slice at a legal one half unit per
	 * slice. With a seam at each end the two cones are computed independently
	 * and meet in the middle, which is exactly the ramp the arm needs.
	 */
	private static final class ArmSeam {
		final int[] lo;
		final int[] hi;

		ArmSeam(int[] lo, int[] hi) {
			this.lo = lo;
			this.hi = hi;
		}

		/** Nearest pinned slice at or before {@code s}, or -1. */
		private static int lastPinned(boolean[] pinned, int s) {
			for (int i = s; i >= 0; i--) {
				if (pinned[i]) {
					return i;
				}
			}
			return -1;
		}

		/** Nearest pinned slice at or after {@code s}, or -1. */
		private static int nextPinned(boolean[] pinned, int s) {
			for (int i = s; i < pinned.length; i++) {
				if (pinned[i]) {
					return i;
				}
			}
			return -1;
		}
	}

	private static ArmSeam analyzeSeam(RoadNetwork.Arm arm, int[][] hardBounds) {
		int sliceCount = arm.slices.size();
		boolean[] pinned = new boolean[sliceCount];
		int[] pinLo = new int[sliceCount];
		int[] pinHi = new int[sliceCount];
		boolean any = false;
		for (int s = 0; s < sliceCount; s++) {
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			for (int cellIdx : arm.slices.get(s)) {
				int[] bound = hardBounds[cellIdx];
				if (bound != null) {
					lo = Math.min(lo, bound[0]);
					hi = Math.max(hi, bound[1]);
				}
			}
			if (lo != Integer.MAX_VALUE) {
				pinned[s] = true;
				pinLo[s] = lo;
				pinHi[s] = hi;
				any = true;
			}
		}
		if (!any) {
			return null;
		}
		int[] coneLo = new int[sliceCount];
		int[] coneHi = new int[sliceCount];
		for (int s = 0; s < sliceCount; s++) {
			int lo = Integer.MAX_VALUE;
			int hi = Integer.MIN_VALUE;
			int back = ArmSeam.lastPinned(pinned, s);
			if (back >= 0) {
				int d = s - back;
				lo = Math.min(lo, pinLo[back] - d);
				hi = Math.max(hi, pinHi[back] + d);
			}
			int fwd = ArmSeam.nextPinned(pinned, s);
			if (fwd >= 0) {
				int d = fwd - s;
				lo = Math.min(lo, pinLo[fwd] - d);
				hi = Math.max(hi, pinHi[fwd] + d);
			}
			coneLo[s] = lo;
			coneHi[s] = hi;
		}
		return new ArmSeam(coneLo, coneHi);
	}

	/**
	 * Fills the per-cell candidate bands: the reference/chase hull with the given
	 * margin, then - in phase 3 - the seam cone.
	 *
	 * <p>The hull is centred on the reference target, not on raw terrain
	 * (review 4 section 5.2). That is the change that makes a span reachable at
	 * all: with a terrain-centred band the only candidates near a canyon floor
	 * were canyon-floor heights, so no cost model could have picked a bridge no
	 * matter how the weights were set. The cell's own terrain and its reference
	 * are still both inside the hull, so a normal village piece sees the same
	 * candidate set as before.
	 *
	 * <p>Seam cells merge their own hard bound outright, so they always have
	 * legal candidates. Every other cell is unioned with the per-slice cone,
	 * which is the smallest widening that still guarantees a legal ramp can
	 * reach the seam.
	 *
	 * @return the widest per-cell band, for the state budget check
	 */
	private static int computeBands(
			RoadNetwork net,
			RoadNetwork.Arm arm,
			int[] chaseFwd,
			int[] chaseBack,
			int margin,
			ArmSeam seam,
			int[][] hardBounds,
			int[] bandLo,
			int[] bandHi,
			boolean[] hasBand
	) {
		int maxBand = 0;
		for (int s = 0; s < arm.slices.size(); s++) {
			int coneLo = seam != null ? seam.lo[s] : Integer.MIN_VALUE;
			int coneHi = seam != null ? seam.hi[s] : Integer.MAX_VALUE;
			for (int cellIdx : arm.slices.get(s)) {
				RoadCell cell = net.cells.get(cellIdx);
				int lo = Math.min(chaseFwd[s], Math.min(chaseBack[s], Math.min(cell.baseP, cell.refP))) - margin;
				int hi = Math.max(chaseFwd[s], Math.max(chaseBack[s], Math.max(cell.baseP, cell.refP))) + margin;
				if (seam != null) {
					int[] own = hardBounds[cellIdx];
					if (own != null) {
						// the seam cell itself: its candidates must intersect the
						// hard bound, which pins it one half unit off the region
						lo = Math.max(lo, own[0]);
						hi = Math.min(hi, own[1]);
					}
					// The cone is intersected, not merged. Any value a cell can
					// hold while still reaching the seam at a legal slope lies in
					// the cone, so anything outside it can only ever produce a
					// counted violation - and merging instead would widen the band
					// by up to the arm length on every slice, which is what pushed
					// wide arms past the state budget and into the region
					// fallback (review 4 probe bench, 4-wide arm). If the
					// reference itself falls outside the cone the two cannot both
					// be satisfied; the band then keeps the reference and the
					// cliff penalty on top counts the difference.
					int narrowedLo = Math.max(lo, coneLo);
					int narrowedHi = Math.min(hi, coneHi);
					if (narrowedLo <= narrowedHi) {
						lo = narrowedLo;
						hi = narrowedHi;
					}
				}
				bandLo[cellIdx] = lo;
				bandHi[cellIdx] = hi;
				hasBand[cellIdx] = true;
				maxBand = Math.max(maxBand, hi - lo + 1);
			}
		}
		return maxBand;
	}

	private static boolean solveArm(RoadNetwork net, RoadNetwork.Arm arm, SolveParams p, int[][] hardBounds, Stats stats) {
		List<int[]> slices = arm.slices;
		int sliceCount = slices.size();
		if (sliceCount == 0) {
			return true;
		}

		int maxSliceWidth = 0;
		for (int[] slice : slices) {
			maxSliceWidth = Math.max(maxSliceWidth, slice.length);
		}
		int window = chooseWindow(maxSliceWidth, p.windowHalf());

		// Reference profile of this arm (review 4 section 5.3). The hard values
		// are the anchors plus, in phase 3, the seam bounds, so a seam is part
		// of the reference's own skeleton instead of a band patch applied
		// afterwards.
		RoadReference.Profile profile = armReference(net, arm, hardBounds, p.fillBudget2(), p.clearBudget2());
		int[] target = profile.target();
		for (int s = 0; s < sliceCount; s++) {
			for (int cellIdx : slices.get(s)) {
				net.cells.get(cellIdx).refP = profile.ref()[s];
			}
		}

		// Chase lines: the legal profile tracking the reference target as closely
		// as legal steps allow, from the left (chaseFwd) and from the right
		// (chaseBack). The target is terrain wherever the terrain is affordable
		// and the level span baseline where it is not, so chasing it tracks a
		// mound but stays on the bridge deck over a canyon. For the deviation
		// objectives the optimum lies pointwise between the two lines, so a
		// per-cell candidate band around [min, max] of the two lines, the cell's
		// own terrain and its reference contains it while staying small on
		// sustained slopes, where a fixed window around P0 would run out of room.
		int[] chaseFwd = new int[sliceCount];
		int[] chaseBack = new int[sliceCount];
		for (int s = 0; s < sliceCount; s++) {
			chaseFwd[s] = s == 0 ? target[0] : clampToStep(target[s], chaseFwd[s - 1]);
		}
		for (int s = sliceCount - 1; s >= 0; s--) {
			chaseBack[s] = s == sliceCount - 1 ? target[s] : clampToStep(target[s], chaseBack[s + 1]);
		}
		ArmSeam seam = hardBounds == null ? null : analyzeSeam(arm, hardBounds);
		int margin = Math.max(0, Math.min(p.windowHalf(), 4));
		int[] bandLo = new int[net.cells.size()];
		int[] bandHi = new int[net.cells.size()];
		boolean[] hasBand = new boolean[net.cells.size()];
		int maxBand = computeBands(net, arm, chaseFwd, chaseBack, margin, seam, hardBounds, bandLo, bandHi, hasBand);
		// State budget guard. The old A3 loop shrank the band symmetrically,
		// which is exactly what used to cut the seam side away and produce the
		// candidate contradiction behind the junction cliff (review 3 F4), and
		// for the flat junction arms it measures it never fired at all. The
		// replacement shrinks only the margin slack around the chase lines -
		// the chase hull and the seam extension keep their shape - and gives up
		// to the ICM fallback only for extreme terrain where even that is too
		// wide (review 3 section 9 flagged the 4-wide+slope case as unproven
		// for A3; this keeps those arms exact instead of flattening them).
		while (margin > 0 && powOf(maxBand, maxSliceWidth) > STATE_BUDGET) {
			margin--;
			maxBand = computeBands(net, arm, chaseFwd, chaseBack, margin, seam, hardBounds, bandLo, bandHi, hasBand);
		}
		if (powOf(maxBand, maxSliceWidth) > STATE_BUDGET) {
			return false; // band too wide for the exact DP: caller falls back to ICM
		}

		int cellCount = 0;
		int edgeCount = 0;
		for (int[] slice : slices) {
			cellCount += slice.length;
		}
		for (int[] slice : slices) {
			for (int cellIdx : slice) {
				for (int dir = 0; dir < 4; dir++) {
					int j = net.neighbors[cellIdx][dir];
					if (j >= 0 && j > cellIdx) {
						edgeCount++;
					}
				}
			}
		}
		CostModel cost = new CostModel(p, cellCount, edgeCount, window);

		// per-slice candidate value lists and per-cell costs
		int[][][] candidates = new int[sliceCount][][];
		long[][][] cellCosts = new long[sliceCount][][];
		int[][][] intraPairs = new int[sliceCount][][];
		long[][][][] intraMatrices = new long[sliceCount][][][];

		for (int s = 0; s < sliceCount; s++) {
			int[] slice = slices.get(s);
			int[][] candSlice = new int[slice.length][];
			long[][] cellCostSlice = new long[slice.length][];
			for (int pos = 0; pos < slice.length; pos++) {
				RoadCell cell = net.cells.get(slice[pos]);
				candSlice[pos] = candidatesFor(cell, window, p, hardBounds, bandLo, bandHi, hasBand, stats);
				cellCostSlice[pos] = new long[candSlice[pos].length];
				for (int v = 0; v < candSlice[pos].length; v++) {
					cellCostSlice[pos][v] = cost.cellCost(cell, candSlice[pos][v]);
				}
			}
			candidates[s] = candSlice;
			cellCosts[s] = cellCostSlice;

			List<int[]> pairs = new ArrayList<>();
			List<long[][]> matrices = new ArrayList<>();
			for (int a = 0; a < slice.length; a++) {
				for (int b = a + 1; b < slice.length; b++) {
					if (areAdjacent(net, slice[a], slice[b])) {
						pairs.add(new int[]{a, b});
						matrices.add(cost.edgeCostMatrix(candSlice[a], candSlice[b]));
					}
				}
			}
			intraPairs[s] = pairs.toArray(new int[0][]);
			intraMatrices[s] = matrices.toArray(new long[0][][]);
		}

		// cross-slice kernels (one cost matrix per aligned position pair)
		long[][][][] kernels = new long[sliceCount][][][];
		int[][] alignedMap = new int[sliceCount][];
		for (int s = 1; s < sliceCount; s++) {
			Object[] built = buildKernel(net, arm, s - 1, s, candidates, cost);
			// built[0] = per-source-position matrix (null when no aligned edge), built[1] = aligned t-position per s-position
			kernels[s] = (long[][][]) built[0];
			alignedMap[s] = (int[]) built[1];
		}

		// forward DP
		long[][] G = new long[sliceCount][];
		G[0] = sliceStateCosts(candidates[0], cellCosts[0], intraPairs[0], intraMatrices[0]);
		for (int s = 1; s < sliceCount; s++) {
			long[] h = transformMinPlus(G[s - 1], candidates[s - 1], candidates[s], kernels[s], alignedMap[s]);
			long[] fs = sliceStateCosts(candidates[s], cellCosts[s], intraPairs[s], intraMatrices[s]);
			for (int t = 0; t < fs.length; t++) {
				fs[t] += h[t];
			}
			G[s] = fs;
		}

		// backtrack
		int[] chosen = new int[sliceCount];
		chosen[sliceCount - 1] = argmin(G[sliceCount - 1]);
		for (int s = sliceCount - 1; s > 0; s--) {
			chosen[s - 1] = backtrackPredecessor(G[s - 1], candidates[s - 1], candidates[s], chosen[s], kernels[s], alignedMap[s]);
		}
		for (int s = 0; s < sliceCount; s++) {
			int[] slice = slices.get(s);
			int[] values = decodeState(candidates[s], chosen[s]);
			for (int pos = 0; pos < slice.length; pos++) {
				net.cells.get(slice[pos]).solvedP = values[pos];
			}
		}
		return true;
	}

	private static int[] armCells(RoadNetwork.Arm arm) {
		int total = 0;
		for (int[] slice : arm.slices) {
			total += slice.length;
		}
		int[] out = new int[total];
		int k = 0;
		for (int[] slice : arm.slices) {
			for (int cell : slice) {
				out[k++] = cell;
			}
		}
		return out;
	}

	/**
	 * Reference profile of one arm. A slice is pinned when it carries an anchor
	 * (a water crossing, fixed at the water surface) or - in phase 3 - a cell
	 * with a seam hard bound; a slice can never carry both at disagreeing
	 * values, and the seam wins because the region it points at is already
	 * solved and frozen. The budgets are the writer's own envelope, so a
	 * reference value inside them can never trip the writer's fallback
	 * re-anchor.
	 */
	private static RoadReference.Profile armReference(RoadNetwork net, RoadNetwork.Arm arm, int[][] hardBounds,
			int fillBudget2, int clearBudget2) {
		int sliceCount = arm.slices.size();
		int[] terrain = new int[sliceCount];
		boolean[] pinned = new boolean[sliceCount];
		int[] pinValue = new int[sliceCount];
		for (int s = 0; s < sliceCount; s++) {
			terrain[s] = sliceReference(net, arm.slices.get(s));
			int pin = 0;
			boolean isPin = false;
			for (int cellIdx : arm.slices.get(s)) {
				RoadCell cell = net.cells.get(cellIdx);
				int[] bound = hardBounds != null && cellIdx < hardBounds.length ? hardBounds[cellIdx] : null;
				if (bound != null) {
					// the seam cell must sit exactly one half unit off the
					// already-solved region height
					int mid = (int) Math.floorDiv((long) bound[0] + bound[1], 2L);
					pin = isPin ? clampToStep(mid, pin) : mid;
					isPin = true;
				} else if (cell.anchor && !isPin) {
					pin = cell.baseP;
					isPin = true;
				}
			}
			pinned[s] = isPin;
			pinValue[s] = pin;
		}
		return RoadReference.forArm(terrain, pinned, pinValue, fillBudget2, clearBudget2);
	}

	/** Reference height of one slice for the reference profile: the median base height. */
	private static int sliceReference(RoadNetwork net, int[] slice) {
		int[] values = new int[slice.length];
		for (int i = 0; i < slice.length; i++) {
			values[i] = net.cells.get(slice[i]).baseP;
		}
		return median(values, slice.length);
	}

	/** Moves the chase line one legal step towards the slice reference. */
	private static int clampToStep(int reference, int previous) {
		return Math.max(previous - 1, Math.min(previous + 1, reference));
	}

	/**
	 * Builds the separable cross-slice kernel. Returns {matrices, aligned}:
	 * matrices[pos] is the |valS x valT| cost matrix between source position
	 * {@code pos} and its aligned target position (null when the cell has no
	 * cross-slice edge), aligned[pos] is the target position index or -1.
	 */
	private static Object[] buildKernel(RoadNetwork net, RoadNetwork.Arm arm, int sliceA, int sliceB, int[][][] candidates, CostModel cost) {
		int[] cellsA = arm.slices.get(sliceA);
		int[] cellsB = arm.slices.get(sliceB);
		int width = cellsA.length;
		long[][][] matrices = new long[width][][];
		int[] aligned = new int[width];
		Arrays.fill(aligned, -1);

		Map<Integer, Integer> posB = new HashMap<>();
		for (int pos = 0; pos < cellsB.length; pos++) {
			RoadCell cell = net.cells.get(cellsB[pos]);
			posB.put(arm.sweepAxis == 0 ? cell.x : cell.z, pos);
		}
		for (int pos = 0; pos < cellsA.length; pos++) {
			RoadCell cellA = net.cells.get(cellsA[pos]);
			int coord = arm.sweepAxis == 0 ? cellA.x : cellA.z;
			Integer other = posB.get(coord);
			if (other == null || !areAdjacent(net, cellsA[pos], cellsB[other])) {
				continue;
			}
			matrices[pos] = cost.edgeCostMatrix(candidates[sliceA][pos], candidates[sliceB][other]);
			aligned[pos] = other;
		}
		return new Object[]{matrices, aligned};
	}

	/**
	 * H(t) = min over source states of [ G(s) + sum_pos k(s_val(pos), t_val(aligned(pos))) ].
	 * Source positions are eliminated one by one; each elimination introduces
	 * the target position it is coupled with. Intermediate domains stay the size
	 * of one slice's state space, so the whole transform is O(width * states * values).
	 *
	 * <p>The inner sweep over {@code v} walks the flat array with a fixed stride
	 * instead of re-encoding the mixed-radix index for every candidate value.
	 * That re-encode was the dominant cost of the entire solve: it turned the
	 * elimination step from O(states * values) into O(states * values * width).
	 */
	private static long[] transformMinPlus(long[] g, int[][] candS, int[][] candT, long[][][] kernels, int[] aligned) {
		int w = candS.length;
		// dims: [s_0..s_{w-1}, t dims in introduction order]; inactive dims have size 1
		int[] dims = new int[2 * w];
		Arrays.fill(dims, 1);
		for (int pos = 0; pos < w; pos++) {
			dims[pos] = candS[pos].length;
		}
		long[] m = g;
		int[] coords = new int[2 * w];
		int[] tDimSlot = new int[2 * w]; // slot of the t dim introduced per position, -1
		Arrays.fill(tDimSlot, -1);
		int nextSlot = w;
		long[] strides = new long[2 * w];

		for (int pos = 0; pos < w; pos++) {
			int tPos = aligned[pos];
			int[] outDims = new int[2 * w];
			System.arraycopy(dims, 0, outDims, 0, 2 * w);
			outDims[pos] = 1; // eliminate source position pos
			int slot = -1;
			if (tPos >= 0) {
				slot = nextSlot++;
				outDims[slot] = candT[tPos].length;
			}
			tDimSlot[pos] = slot;

			// Flat-index strides of the CURRENT domain: encodeMixedRadix sums
			// coords[i] * strides[i], so holding every coordinate but coords[pos]
			// fixed and sweeping v moves the index by exactly v * strides[pos].
			strides[2 * w - 1] = 1;
			for (int i = 2 * w - 2; i >= 0; i--) {
				strides[i] = strides[i + 1] * dims[i + 1];
			}
			long step = strides[pos];

			long outSize = sizeOf(outDims);
			long[] out = new long[(int) outSize];
			long[][] kernel = kernels[pos];
			for (long outIdx = 0; outIdx < outSize; outIdx++) {
				decodeMixedRadix(outDims, outIdx, coords);
				int tValue = slot >= 0 ? coords[slot] : 0;
				// m's domain does not include the t dim introduced by this step,
				// so its coordinate must read as zero when indexing into m
				if (slot >= 0) {
					coords[slot] = 0;
				}
				// outDims[pos] is 1, so the decode above already left coords[pos]
				// at 0 and the base index is complete without touching it
				long base = 0;
				for (int i = 0; i < 2 * w; i++) {
					base += coords[i] * strides[i];
				}
				long best = Long.MAX_VALUE;
				for (int v = 0; v < dims[pos]; v++) {
					long k = kernel != null ? kernel[v][tValue] : 0L;
					long value = m[(int) (base + v * step)] + k;
					if (value < best) {
						best = value;
					}
				}
				out[(int) outIdx] = best;
			}
			dims = outDims;
			m = out;
		}

		// reorder m (indexed by the t dims coupled to source positions) into the
		// full target state enumeration; target positions without a source
		// partner are unconstrained by the kernel, so states that agree on all
		// coupled positions receive the same value
		long[] result = new long[stateCount(candT)];
		int[] dimsT = dims(candT);
		int[] tCoords = new int[candT.length];
		int[] mCoords = new int[dims.length];
		long total = sizeOf(dimsT);
		for (long tIdx = 0; tIdx < total; tIdx++) {
			decodeMixedRadix(dimsT, tIdx, tCoords);
			Arrays.fill(mCoords, 0);
			for (int pos = 0; pos < w; pos++) {
				if (aligned[pos] >= 0 && tDimSlot[pos] >= 0) {
					mCoords[tDimSlot[pos]] = tCoords[aligned[pos]];
				}
			}
			result[(int) tIdx] = m[(int) encodeMixedRadix(dims, mCoords)];
		}
		return result;
	}

	/**
	 * Argmin over source states of [ G(s) + K(s, fixed target state) ], with
	 * per-step argmin tables so the predecessor state can be reconstructed.
	 *
	 * <p>Same strided walk as {@link #transformMinPlus}: the candidate sweep
	 * over {@code v} advances the flat index by {@code strides[pos]} instead of
	 * re-encoding the whole mixed-radix index once per value.
	 */
	private static int backtrackPredecessor(long[] g, int[][] candS, int[][] candT, int tState, long[][][] kernels, int[] aligned) {
		int w = candS.length;
		// candidate INDICES of the fixed target state (kernel matrices are
		// indexed by candidate index, not by height value)
		int[] tIndices = new int[candT.length];
		decodeMixedRadix(dims(candT), tState, tIndices);
		int[] dims = new int[w];
		for (int pos = 0; pos < w; pos++) {
			dims[pos] = candS[pos].length;
		}
		long[] m = g;
		int[] coords = new int[w];
		long[] strides = new long[w];
		int[][] argmins = new int[w][];
		int[][] argminDims = new int[w][];

		for (int pos = 0; pos < w; pos++) {
			int[] outDims = new int[w];
			System.arraycopy(dims, 0, outDims, 0, w);
			outDims[pos] = 1;
			int tPos = aligned[pos];
			int tValue = tPos >= 0 ? tIndices[tPos] : 0;

			strides[w - 1] = 1;
			for (int i = w - 2; i >= 0; i--) {
				strides[i] = strides[i + 1] * dims[i + 1];
			}
			long step = strides[pos];

			long outSize = sizeOf(outDims);
			long[] out = new long[(int) outSize];
			int[] argmin = new int[(int) outSize];
			long[][] kernel = kernels[pos];
			for (long outIdx = 0; outIdx < outSize; outIdx++) {
				decodeMixedRadix(outDims, outIdx, coords);
				// outDims[pos] is 1, so the decode already left coords[pos] at 0
				long base = 0;
				for (int i = 0; i < w; i++) {
					base += coords[i] * strides[i];
				}
				long best = Long.MAX_VALUE;
				int bestV = 0;
				for (int v = 0; v < dims[pos]; v++) {
					long k = kernel != null ? kernel[v][tValue] : 0L;
					long value = m[(int) (base + v * step)] + k;
					if (value < best) {
						best = value;
						bestV = v;
					}
				}
				out[(int) outIdx] = best;
				argmin[(int) outIdx] = bestV;
			}
			argmins[pos] = argmin;
			argminDims[pos] = outDims;
			dims = outDims;
			m = out;
		}

		// reconstruct the source state in reverse elimination order: the argmin
		// table of step pos is indexed by the not-yet-reconstructed (later)
		// source positions, which are already known when walking backwards
		int[] sCoords = new int[w];
		for (int pos = w - 1; pos >= 0; pos--) {
			int[] idxCoords = new int[w];
			System.arraycopy(sCoords, 0, idxCoords, 0, w);
			idxCoords[pos] = 0;
			sCoords[pos] = argmins[pos][(int) encodeMixedRadix(argminDims[pos], idxCoords)];
		}
		return (int) encodeMixedRadix(dims(candS), sCoords);
	}

	private static int stateCount(int[][] candidates) {
		long total = 1;
		for (int[] cand : candidates) {
			total *= cand.length;
		}
		return (int) total;
	}

	private static int[] dims(int[][] candidates) {
		int[] dims = new int[candidates.length];
		for (int i = 0; i < dims.length; i++) {
			dims[i] = candidates[i].length;
		}
		return dims;
	}

	private static long sizeOf(int[] dims) {
		long total = 1;
		for (int d : dims) {
			total *= d;
		}
		return total;
	}

	private static long encodeMixedRadix(int[] dims, int[] coords) {
		long idx = 0;
		for (int i = 0; i < dims.length; i++) {
			idx = idx * dims[i] + coords[i];
		}
		return idx;
	}

	private static void decodeMixedRadix(int[] dims, long index, int[] out) {
		for (int i = dims.length - 1; i >= 0; i--) {
			out[i] = (int) (index % dims[i]);
			index /= dims[i];
		}
	}

	private static int[] decodeState(int[][] candidates, int stateIndex) {
		int[] dims = dims(candidates);
		int[] coords = new int[dims.length];
		decodeMixedRadix(dims, stateIndex, coords);
		int[] values = new int[dims.length];
		for (int i = 0; i < dims.length; i++) {
			values[i] = candidates[i][coords[i]];
		}
		return values;
	}

	private static int argmin(long[] values) {
		int best = 0;
		for (int i = 1; i < values.length; i++) {
			if (values[i] < values[best]) {
				best = i;
			}
		}
		return best;
	}

	private static int chooseWindow(int width, int maxWindow) {
		int m = Math.max(1, maxWindow);
		while (m > 1 && powOf(2 * m + 1, width) > STATE_BUDGET) {
			m--;
		}
		return m;
	}

	private static long powOf(long base, int exp) {
		long result = 1;
		for (int i = 0; i < exp; i++) {
			result *= base;
			if (result > 1_000_000_000L) {
				return result;
			}
		}
		return result;
	}

	private static boolean areAdjacent(RoadNetwork net, int a, int b) {
		for (int dir = 0; dir < 4; dir++) {
			if (net.neighbors[a][dir] == b) {
				return true;
			}
		}
		return false;
	}

	private static int[] candidatesFor(RoadCell cell, int window, SolveParams p, int[][] hardBounds, int[] bandLo, int[] bandHi, boolean[] hasBand, Stats stats) {
		if (cell.anchor) {
			return new int[]{cell.baseP};
		}
		int lo;
		int hi;
		if (hasBand != null && hasBand[cell.index]) {
			// the chase band replaces the naive P0 window (it contains it where
			// it matters and extends it on sustained slopes)
			lo = bandLo[cell.index];
			hi = bandHi[cell.index];
		} else {
			lo = cell.baseP - window;
			hi = cell.baseP + window;
		}
		if (!p.allowCut()) {
			lo = Math.max(lo, cell.baseP);
		}
		if (hardBounds != null && hardBounds[cell.index] != null) {
			lo = Math.max(lo, hardBounds[cell.index][0]);
			hi = Math.min(hi, hardBounds[cell.index][1]);
		}
		if (lo > hi) {
			// contradictory bounds: keep the current value as the only option.
			// With the seam extension this only happens when two region
			// neighbours genuinely disagree (or allowCut forbids the only
			// reachable seam value) - no longer silent, but counted (review 3
			// P0-A item 1)
			stats.candidateContradictions++;
			return new int[]{cell.solvedP};
		}
		int[] out = new int[hi - lo + 1];
		for (int v = lo; v <= hi; v++) {
			out[v - lo] = v;
		}
		return out;
	}

	/** Cartesian product state costs of one slice (cell costs + intra-slice edge costs). */
	private static long[] sliceStateCosts(int[][] candidates, long[][] cellCosts, int[][] pairs, long[][][] matrices) {
		long[] out = new long[stateCount(candidates)];
		int w = candidates.length;
		int[] dims = dims(candidates);
		int[] coords = new int[w];
		long total = sizeOf(dims);
		for (long idx = 0; idx < total; idx++) {
			decodeMixedRadix(dims, idx, coords);
			long cost = 0;
			for (int pos = 0; pos < w; pos++) {
				cost += cellCosts[pos][coords[pos]];
			}
			for (int e = 0; e < pairs.length; e++) {
				int[] pair = pairs[e];
				cost += matrices[e][coords[pair[0]]][coords[pair[1]]];
			}
			out[(int) idx] = cost;
		}
		return out;
	}

	// ------------------------------------------------------------------
	// region ICM
	// ------------------------------------------------------------------

	private static void solveRegion(RoadNetwork net, int[] region, SolveParams p, BitSet icmMembership, Stats stats) {
		// Review 3 P0-B: seed the region with a feasible gradient instead of a
		// single median platform. The old init flattened the whole crossing to
		// the boundary median, which discarded the crossing's own ramp and
		// pushed all of the error onto the two seams as cliffs. Per cell the
		// solved heights just outside the region are interpolated along its row
		// and its column; both interpolations are clamped to a legal staircase
		// (|dP| <= 1 per cell) and holes (cells whose row AND column walks both
		// reach the network edge, e.g. the corners of a core whose arms sit at
		// the edge midpoints) are filled from their initialised neighbours, so
		// the ICM starts from a field it can actually legalise. The ICM rounds
		// plus the hard passes below still own the final legality.
		int[] init = new int[region.length];
		boolean[] filled = new boolean[region.length];
		boolean anyReference = false;
		for (int i = 0; i < region.length; i++) {
			RoadCell rc = net.cells.get(region[i]);
			if (rc.anchor) {
				continue;
			}
			int rowValue = interpolateFromBoundary(net, region[i], icmMembership, true);
			int colValue = interpolateFromBoundary(net, region[i], icmMembership, false);
			if (rowValue != Integer.MIN_VALUE && colValue != Integer.MIN_VALUE) {
				long sum = (long) rowValue + colValue;
				init[i] = (int) ((sum + ((sum >= 0) ? 1 : -1)) / 2); // deterministic half-away-from-zero
			} else if (rowValue != Integer.MIN_VALUE) {
				init[i] = rowValue;
			} else if (colValue != Integer.MIN_VALUE) {
				init[i] = colValue;
			} else {
				continue; // hole: filled below from initialised neighbours
			}
			// review 4: the seed becomes the region reference (legalised below).
			// The ICM measures its deviation against it, so anchoring on a raw
			// terrain platform would be the exact thing that tore the junction
			// apart in probe M2 - and anchoring on an ILLEGAL seed would make it
			// worse, preserving a step the ICM can no longer climb away from.
			filled[i] = true;
			anyReference = true;
		}
		if (!anyReference) {
			// no boundary reference anywhere (a region with no adjacent solved
			// cell): the old platform seed is the only sensible fallback
			int[] base = new int[region.length];
			int c = 0;
			for (int r : region) {
				base[c++] = net.cells.get(r).baseP;
			}
			int platform = median(base, c);
			for (int i = 0; i < region.length; i++) {
				if (!net.cells.get(region[i]).anchor) {
					init[i] = platform;
					filled[i] = true;
				}
			}
		}
		for (int pass = 0; pass < region.length; pass++) {
			boolean changed = false;
			for (int i = 0; i < region.length; i++) {
				if (filled[i] || net.cells.get(region[i]).anchor) {
					continue;
				}
				long sum = 0;
				int n = 0;
				for (int dir = 0; dir < 4; dir++) {
					int j = net.neighbors[region[i]][dir];
					if (j >= 0 && icmMembership.get(j)) {
						int k = regionIndexOf(region, j);
						if (k >= 0 && filled[k]) {
							sum += init[k];
							n++;
						}
					}
				}
				if (n > 0) {
					init[i] = (int) ((sum + ((sum >= 0) ? n / 2 : -(n / 2))) / n);
					filled[i] = true;
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
		legaliseRegionReference(net, region, init, filled);
		for (int i = 0; i < region.length; i++) {
			if (filled[i]) {
				// Review 4: the seed, once legal, IS this region's reference, so
				// the ICM below anchors on a staircase that already respects the
				// boundary cones instead of on raw terrain. Fallback arms keep
				// the reference their arm pass gave them.
				if (net.regionCells.get(region[i])) {
					net.cells.get(region[i]).refP = init[i];
				}
				net.cells.get(region[i]).solvedP = init[i];
			}
		}

		// ICM rounds: x runs then z runs with soft transitions
		for (int round = 0; round < Math.max(0, p.icmRounds()); round++) {
			scanRuns(net, region, true, true, p, stats);
			scanRuns(net, region, false, true, p, stats);
		}
		// final hard passes: every 4-adjacency edge ends up exactly satisfied
		scanRuns(net, region, true, false, p, stats);
		scanRuns(net, region, false, false, p, stats);
	}

	/**
	 * Turns the region seed into a legal staircase before it is used as the
	 * region's reference (review 4).
	 *
	 * <p>The seed averages a row interpolation with a column interpolation, so
	 * on a crossing with arms on opposite sides it can step by more than one
	 * half unit between adjacent cells - averaging two legal staircases that
	 * disagree is not itself legal. That was harmless while the seed was only a
	 * starting point the ICM was free to walk away from. It stops being
	 * harmless once the seed becomes the reference the ICM measures deviation
	 * against: the ICM would then be pulled towards an illegal field and hold
	 * the step, which is exactly the interior cliff review 4 set out to remove.
	 *
	 * <p>Alternating row and column forward/backward clamps, a few times over.
	 * It is not optimal - the ICM still owns the real optimisation - it only
	 * guarantees the field it is handed can be walked legally.
	 */
	private static void legaliseRegionReference(RoadNetwork net, int[] region, int[] init, boolean[] filled) {
		for (int pass = 0; pass < 4; pass++) {
			for (boolean alongX : new boolean[]{true, false}) {
				for (int start = 0; start < region.length; start++) {
					if (!filled[start]) {
						continue;
					}
					int backDir = alongX ? RoadNetwork.DIR_WEST : RoadNetwork.DIR_NORTH;
					int fwdDir = alongX ? RoadNetwork.DIR_EAST : RoadNetwork.DIR_SOUTH;
					int previous = net.neighbors[region[start]][backDir];
					if (previous >= 0 && regionIndexOf(region, previous) >= 0) {
						continue; // not the head of its run
					}
					List<Integer> run = new ArrayList<>();
					int j = region[start];
					while (j >= 0) {
						int k = regionIndexOf(region, j);
						if (k < 0 || !filled[k]) {
							break;
						}
						run.add(k);
						j = net.neighbors[j][fwdDir];
					}
					for (int i = 1; i < run.size(); i++) {
						int p = init[run.get(i - 1)];
						init[run.get(i)] = Math.max(p - 1, Math.min(p + 1, init[run.get(i)]));
					}
					for (int i = run.size() - 2; i >= 0; i--) {
						int q = init[run.get(i + 1)];
						init[run.get(i)] = Math.max(q - 1, Math.min(q + 1, init[run.get(i)]));
					}
				}
			}
		}
	}

	private static int regionIndexOf(int[] region, int cell) {
		for (int i = 0; i < region.length; i++) {
			if (region[i] == cell) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * One boundary interpolation for a region cell (review 3 P0-B). Walks along
	 * one axis to the region edge on both sides and interpolates between the
	 * solved heights found there, clamped to the legal staircase reachable from
	 * the two references: from the west value {@code W} at distance {@code k}
	 * the cell can only hold {@code [W-k, W+k]} without breaking the step
	 * constraint, and symmetrically for the east value. When the two reachable
	 * ranges do not meet (the endpoints cannot be joined legally at 1 half unit
	 * per cell) the near side wins. Returns {@link Integer#MIN_VALUE} when the
	 * axis has no outside reference on either side.
	 */
	private static int interpolateFromBoundary(RoadNetwork net, int cell, BitSet icmMembership, boolean alongX) {
		int backDir = alongX ? RoadNetwork.DIR_WEST : RoadNetwork.DIR_NORTH;
		int fwdDir = alongX ? RoadNetwork.DIR_EAST : RoadNetwork.DIR_SOUTH;
		int backValue = Integer.MIN_VALUE;
		int backDist = 0;
		int j = cell;
		while (true) {
			int next = net.neighbors[j][backDir];
			if (next < 0) {
				break;
			}
			backDist++;
			if (!icmMembership.get(next)) {
				backValue = net.cells.get(next).solvedP;
				break;
			}
			j = next;
		}
		int fwdValue = Integer.MIN_VALUE;
		int fwdDist = 0;
		j = cell;
		while (true) {
			int next = net.neighbors[j][fwdDir];
			if (next < 0) {
				break;
			}
			fwdDist++;
			if (!icmMembership.get(next)) {
				fwdValue = net.cells.get(next).solvedP;
				break;
			}
			j = next;
		}
		if (backValue == Integer.MIN_VALUE && fwdValue == Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		if (backValue == Integer.MIN_VALUE) {
			return fwdValue;
		}
		if (fwdValue == Integer.MIN_VALUE) {
			return backValue;
		}
		// integer lerp backValue -> fwdValue, clamped into the legal staircase
		int span = backDist + fwdDist;
		long linear = backValue + Math.round((double) (fwdValue - backValue) * backDist / span);
		long lo = Math.max((long) backValue - backDist, (long) fwdValue - fwdDist);
		long hi = Math.min((long) backValue + backDist, (long) fwdValue + fwdDist);
		if (lo > hi) {
			// endpoints cannot be joined legally: stay reachable from the near side
			lo = (long) backValue - backDist;
			hi = (long) backValue + backDist;
		}
		return (int) Math.max(lo, Math.min(hi, linear));
	}

	/**
	 * Re-optimizes every maximal run of region cells along one axis.
	 * {@code rowsAlongX}: runs share z when true, share x when false.
	 * {@code soft}: |dP| > 1 transitions are allowed at penalty cost; when
	 * false the transitions are hard so the pass cannot introduce violations.
	 */
	private static void scanRuns(RoadNetwork net, int[] region, boolean rowsAlongX, boolean soft, SolveParams p, Stats stats) {
		BitSet remaining = new BitSet(net.cells.size());
		for (int cell : region) {
			remaining.set(cell);
		}
		int backDir = rowsAlongX ? RoadNetwork.DIR_WEST : RoadNetwork.DIR_NORTH;
		int fwdDir = rowsAlongX ? RoadNetwork.DIR_EAST : RoadNetwork.DIR_SOUTH;
		for (int cell : region) {
			if (!remaining.get(cell)) {
				continue;
			}
			List<Integer> run = new ArrayList<>();
			run.add(cell);
			remaining.clear(cell);
			int j = net.neighbors[cell][backDir];
			while (j >= 0 && remaining.get(j)) {
				run.add(0, j);
				remaining.clear(j);
				j = net.neighbors[j][backDir];
			}
			j = net.neighbors[cell][fwdDir];
			while (j >= 0 && remaining.get(j)) {
				run.add(j);
				remaining.clear(j);
				j = net.neighbors[j][fwdDir];
			}
			optimizeRun(net, run, rowsAlongX, soft, p, stats);
		}
	}

	private static void optimizeRun(RoadNetwork net, List<Integer> run, boolean rowsAlongX, boolean soft, SolveParams p, Stats stats) {
		int n = run.size();
		if (n == 0) {
			return;
		}
		CostModel cost = new CostModel(p, n, n + 4, Math.max(1, p.windowHalf()));
		int[][] candidates = new int[n][];
		long[][] cellCosts = new long[n][];
		for (int i = 0; i < n; i++) {
			RoadCell cell = net.cells.get(run.get(i));
			candidates[i] = runCandidates(net, cell, rowsAlongX, p, stats);
			cellCosts[i] = new long[candidates[i].length];
			for (int v = 0; v < candidates[i].length; v++) {
				cellCosts[i][v] = cost.cellCost(cell, candidates[i][v]);
			}
		}
		long[][][] trans = new long[Math.max(0, n - 1)][][];
		for (int i = 0; i < n - 1; i++) {
			trans[i] = cost.transitionMatrix(candidates[i], candidates[i + 1], soft);
		}
		long[] dp = new long[candidates[0].length];
		System.arraycopy(cellCosts[0], 0, dp, 0, dp.length);
		int[][] back = new int[n][];
		for (int i = 1; i < n; i++) {
			long[] next = new long[candidates[i].length];
			back[i] = new int[candidates[i].length];
			Arrays.fill(next, Long.MAX_VALUE);
			for (int v = 0; v < candidates[i].length; v++) {
				long best = Long.MAX_VALUE;
				int bestU = 0;
				for (int u = 0; u < candidates[i - 1].length; u++) {
					if (dp[u] >= Long.MAX_VALUE / 2) {
						continue; // unreachable in a hard pass
					}
					long c = dp[u] + trans[i - 1][u][v];
					if (c < best) {
						best = c;
						bestU = u;
					}
				}
				if (best >= Long.MAX_VALUE) {
					next[v] = Long.MAX_VALUE;
				} else {
					next[v] = best + cellCosts[i][v];
					back[i][v] = bestU;
				}
			}
			dp = next;
		}
		int best = argmin(dp);
		if (dp[best] >= Long.MAX_VALUE) {
			return; // infeasible hard pass: keep the current values
		}
		int[] path = new int[n];
		path[n - 1] = best;
		for (int i = n - 1; i > 0; i--) {
			path[i - 1] = back[i][path[i]];
		}
		for (int i = 0; i < n; i++) {
			net.cells.get(run.get(i)).solvedP = candidates[i][path[i]];
		}
	}

	/**
	 * Candidates for one cell inside an ICM run: the window around the cell's
	 * REFERENCE, intersected with the hard bounds from the fixed orthogonal
	 * neighbours.
	 *
	 * <p>Review 4 section 4.3 item 2: this used to be a window around raw
	 * {@code baseP}, i.e. centred on the terrain. A legal ramp seeded by the
	 * boundary cones would therefore be re-centred on the terrain inside the
	 * very pass that is supposed to legalise it, which is what flattened the
	 * M2 street back to a low line and tore the junction. Centring on the
	 * reference - which already carries the cone structure and the span level -
	 * keeps the seed's discipline.
	 */
	private static int[] runCandidates(RoadNetwork net, RoadCell cell, boolean rowsAlongX, SolveParams p, Stats stats) {
		if (cell.anchor) {
			return new int[]{cell.baseP};
		}
		int window = Math.max(1, p.windowHalf());
		int centre = cell.refP;
		int lo = p.allowCut() ? centre - window : Math.max(centre, cell.baseP);
		int hi = centre + window;
		int perpA = rowsAlongX ? RoadNetwork.DIR_NORTH : RoadNetwork.DIR_WEST;
		int perpB = rowsAlongX ? RoadNetwork.DIR_SOUTH : RoadNetwork.DIR_EAST;
		for (int dir : new int[]{perpA, perpB}) {
			int j = net.neighbors[cell.index][dir];
			if (j >= 0) {
				int v = net.cells.get(j).solvedP;
				lo = Math.max(lo, v - 1);
				hi = Math.min(hi, v + 1);
			}
		}
		if (lo > hi) {
			// orthogonal clamps disagree with the window: keep the current
			// value, but never silently (review 3 section 3-3)
			stats.candidateContradictions++;
			return new int[]{cell.solvedP};
		}
		int[] out = new int[hi - lo + 1];
		for (int v = lo; v <= hi; v++) {
			out[v - lo] = v;
		}
		return out;
	}

	private static int median(int[] values, int count) {
		if (count <= 0) {
			return 0;
		}
		int[] copy = Arrays.copyOf(values, count);
		Arrays.sort(copy);
		return copy[copy.length / 2];
	}

	// ------------------------------------------------------------------
	// cost model
	// ------------------------------------------------------------------

	/**
	 * Lexicographic cost packed into a single long, decomposable into per-cell
	 * and per-edge terms. Deviation is always measured against the cell's
	 * REFERENCE (review 4 section 5.3), never against raw terrain.
	 * <ul>
	 *   <li>SMOOTH_FIRST (default): primary = penalty*sum(excess), secondary = sum|P-P_ref|, tertiary = sum|dP|</li>
	 *   <li>MIN_DEVIATION: primary = sum|P-P_ref| + penalty*sum(excess), secondary = sum|dP|</li>
	 *   <li>SHORTEST_CATCHUP: primary = deviating cells, secondary = sum|P-P_ref| + penalty*sum(excess), tertiary = sum|dP|</li>
	 *   <li>HYBRID: primary = w1*sum|P-P_ref| + penalty*sum(excess), secondary = w2*sum|dP|</li>
	 * </ul>
	 *
	 * <p>Review 4 changed SMOOTH_FIRST's middle and bottom rank. It used to be
	 * cliff &gt; steps &gt; deviation, which measured out as: levelling a 1-block
	 * mound cost 0 steps and 24 half units of deviation, following it cost 12
	 * steps and 6. "Smooth first" was implemented as "flat first", so the road
	 * ran visibly off the terrain on any piece of rolling ground. Putting
	 * deviation above steps makes the default do what it says. On its own that
	 * swap would have made canyon diving strictly worse - plunging to the floor
	 * is the zero-deviation answer - which is why the reference exists: over a
	 * canyon the reference is not the floor but the level span between the rims,
	 * so bridging is both smooth and deviation-free and the swap costs nothing.
	 *
	 * <p>SHORTEST_CATCHUP keeps its old shape: its whole point is that the step
	 * count is what it optimises, and the deviating-cell count already expresses
	 * that.
	 */
	private static final class CostModel {
		/** Deviations beyond this many half units all cost the same (overflow guard, report 2.1/5-⑤). */
		private static final int EXCESS_SATURATION = 1 << 12;

		private final Objective objective;
		private final int penalty;
		private final int hybridW1;
		private final int hybridW2 = 16;
		private final long s2;
		private final long s1;
		private final long sDev;
		/** SMOOTH_FIRST scales: sLow = step rank, sMid = deviation rank, sTop = cliff penalty rank. */
		private final long sLow;
		private final long sMid;
		private final long sTop;

		CostModel(SolveParams p, int cellCount, int edgeCount, int window) {
			this.objective = p.objective();
			this.penalty = Math.max(0, p.stepViolationPenalty());
			this.hybridW1 = Math.max(1, p.hybridWeightScaled());
			long stepScale = objective == Objective.HYBRID ? hybridW2 : 1;
			long w = Math.max(1, window);
			long s2v = Math.max(1, Math.max(1, edgeCount) * 4L * w * stepScale + 1);
			long sDevV = Math.max(1, Math.max(1, cellCount) * 4L * w + 1);
			long s1v = Math.max(1, Math.max(1, cellCount) + 1);
			// Report 2.1/5-⑤: the lexicographic products (penalty|hybridW1) *
			// excess * [s1 *] sDev * s2 can wrap around for cap-sized components.
			// Deviations are saturated at EXCESS_SATURATION and the scales are
			// clamped so the SUMMED worst case over all cells+edges of the
			// component stays below Long.MAX_VALUE/8 (the DP accumulates every
			// per-cell and per-edge term). The finest scale (s2) is squeezed
			// first: flattening it only blurs the lowest lexicographic rank, the
			// coarse ranks survive as long as mathematically possible. Ordinary
			// sizes never hit the clamp, so the classic scales are kept verbatim.
			long items = Math.max(1, (long) Math.max(1, cellCount) + Math.max(0, edgeCount));
			long top = ((long) Math.max(1, Math.max(this.penalty, this.hybridW1)) + hybridW2 + 2) * EXCESS_SATURATION;
			long room = (Long.MAX_VALUE / 8) / Math.max(1, satMul(items, top));
			long coarseAbove = objective == Objective.SHORTEST_CATCHUP ? satMul(s1v, sDevV) : 1;
			if (satMul(coarseAbove, s2v) > room) {
				s2v = Math.max(1, room / Math.max(1, coarseAbove));
				if (objective == Objective.SHORTEST_CATCHUP && satMul(s1v, sDevV) > room) {
					sDevV = Math.max(1, room / Math.max(1, s1v));
					if (satMul(s1v, sDevV) > room) {
						s1v = Math.max(1, room / Math.max(1, sDevV));
					}
				}
			}
			this.s2 = s2v;
			this.sDev = sDevV;
			// SHORTEST_CATCHUP lexicographic order: (step-violation penalty,
			// deviating cells, sum|P-P0|, sum|dP|) - the penalty sits on top so
			// legal profiles always win and jumps only appear when no legal
			// profile fits the geometry
			this.s1 = s1v;

// SMOOTH_FIRST scales (strict lexicographic, overflow-clamped):
		// sLow: step rank (1 unit); sMid: deviation rank (> max total step);
		// sTop: cliff penalty rank (> max total deviation). Clamped to room so the
			// component worst case stays under Long.MAX_VALUE/8; on huge
			// components the ranks blur gracefully instead of wrapping.
			long sLowV = 1;
			long sMidV = satAdd(satMul(Math.max(1, edgeCount), EXCESS_SATURATION), 1);
			long sTopV = satAdd(satDiv(satMul(Math.max(1, cellCount), satMul(EXCESS_SATURATION, sMidV)), Math.max(1, this.penalty)), 1);
			long perItem = satMul(Math.max(1, this.penalty), EXCESS_SATURATION);
			long roomSmooth = (Long.MAX_VALUE / 8) / Math.max(1, satMul(items, Math.max(1, perItem)));
			if (sTopV > roomSmooth) {
				sTopV = Math.max(1, roomSmooth);
			}
			if (sMidV > sTopV) {
				sMidV = sTopV;
			}
			this.sLow = sLowV;
			this.sMid = sMidV;
			this.sTop = sTopV;
		}

		/** Multiplication saturated at Long.MAX_VALUE (non-negative operands only). */
		private static long satMul(long a, long b) {
			if (a <= 0 || b <= 0) {
				return 0;
			}
			long r = a * b;
			return r / b == a ? r : Long.MAX_VALUE;
		}

		/** Addition saturated at Long.MAX_VALUE (non-negative operands only). */
		private static long satAdd(long a, long b) {
			long r = a + b;
			return r < 0 ? Long.MAX_VALUE : r;
		}

		/** Division (non-negative operands; zero divisor returns the numerator). */
		private static long satDiv(long a, long b) {
			if (b <= 0) {
				return a;
			}
			return a / b;
		}

		long cellCost(RoadCell cell, int value) {
			int dev = Math.min(Math.abs(value - cell.refP), EXCESS_SATURATION);
			switch (objective) {
				case MIN_DEVIATION:
					return dev * s2;
				case SHORTEST_CATCHUP:
					return (dev != 0 ? 1L : 0L) * s1 * sDev * s2 + dev * s2;
				case SMOOTH_FIRST:
					return dev * sMid;
				default:
					return (long) hybridW1 * dev * s2;
			}
		}

		long[][] edgeCostMatrix(int[] valuesA, int[] valuesB) {
			long[][] out = new long[valuesA.length][valuesB.length];
			for (int a = 0; a < valuesA.length; a++) {
				for (int b = 0; b < valuesB.length; b++) {
					out[a][b] = edgeCost(valuesA[a], valuesB[b]);
				}
			}
			return out;
		}

		long[][] transitionMatrix(int[] valuesA, int[] valuesB, boolean soft) {
			long[][] out = new long[valuesA.length][valuesB.length];
			for (int a = 0; a < valuesA.length; a++) {
				for (int b = 0; b < valuesB.length; b++) {
					int delta = Math.abs(valuesA[a] - valuesB[b]);
					if (!soft && delta > 1) {
						out[a][b] = Long.MAX_VALUE / 4;
					} else {
						out[a][b] = edgeCost(valuesA[a], valuesB[b]);
					}
				}
			}
			return out;
		}

		private long edgeCost(int a, int b) {
			int delta = Math.min(Math.abs(a - b), EXCESS_SATURATION);
			int excess = Math.max(0, delta - 1);
			switch (objective) {
				case MIN_DEVIATION:
					return (long) penalty * excess * s2 + delta;
				case SHORTEST_CATCHUP:
					// penalty layer must use the same top-layer scale as the
					// deviating-cell term in cellCost
					return (long) penalty * excess * s1 * sDev * s2 + delta;
				case SMOOTH_FIRST:
					// review 4: strict ranks, deviation above steps. Bridging a
					// canyon stays free because the reference over a canyon is
					// the level span, not the floor.
					return (long) penalty * excess * sTop + (long) delta * sLow;
				default:
					return (long) penalty * excess * s2 + (long) hybridW2 * delta;
			}
		}
	}
}

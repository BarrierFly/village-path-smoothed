/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import dev.peoplecanfly.village_path_smoothed.VillagePathSmoothedConfig.Objective;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
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
 * <p>The objective is lexicographic (see {@link CostModel}); SMOOTH_FIRST, the
 * default, ranks the cliff penalty on top, the TOTAL STEP COUNT in the middle
 * and the deviation from raw terrain (each cell's {@code baseP}) at the
 * bottom - "smooth first" literally means fewest half-block steps: cut through
 * rolling ground where the candidate band allows it, follow sustained grades
 * where it does not. Water-crossing cells carry the water surface as a
 * preferred value (their baseP), not as a fixed anchor, so a smoother profile
 * may leave the water level; every such cell is counted in
 * {@link SolveStats#waterAnchorDeviations()}.
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

	/**
	 * Cap on the polish pass's projection sweeps (fix plan v4 A-1). The sweep only
	 * runs for cells whose already-fixed neighbours disagree, and it converges -
	 * but cyclic projection carries information one hop per sweep, so the cap has
	 * to cover the network's diameter (a few hundred for a large village). Each
	 * sweep is linear in the edge count and the loop exits as soon as nothing
	 * moves, so the cap is a worst-case bound, not a cost. Whatever survives it is
	 * reported as a residual step violation, never silently.
	 */
	private static final int PROJECTION_SWEEPS = 512;

public record SolveParams(
		Objective objective,
		int hybridWeightScaled,
		int stepViolationPenalty,
		int windowHalf,
		int icmRounds,
		boolean allowCut
) {
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
	 * @param waterAnchorDeviations water-crossing cells whose solved surface
	 *        deviates from the water surface. The anchor is soft (fix plan v2
	 *        R4): the water level is the cell's baseP and therefore its
	 *        deviation target, but a smoother profile may leave it, and this
	 *        counts how often it did
	 * @param polish what the A-1 polish pass had to do to make the profile legal
	 */
	public record SolveStats(int candidateContradictions, int residualViolations, int worstStep,
			int waterAnchorDeviations, int phase3Refusals, PolishStats polish) {
		public static final SolveStats EMPTY = new SolveStats(0, 0, 0, 0, 0, PolishStats.EMPTY);

		/** Cells the polish pass had to move (fix plan v4 A-1, decision N3-a). */
		public int polishMoved() {
			return polish.movedCells();
		}

		/** Largest single-cell move the polish made, in half-block units. */
		public int polishMaxDelta() {
			return polish.maxDelta();
		}

		/** 90th percentile of the polish's per-cell moves, in half-block units. */
		public int polishDeltaP90() {
			return polish.deltaP90();
		}

		/** Cells whose two fixed neighbours disagreed and forced a midpoint. */
		public int polishConflicts() {
			return polish.conflictingNeighbours();
		}
	}

	/** What the polish pass did (fix plan v4 A-1, decision N3-a). */
	public record PolishStats(int movedCells, int maxDelta, int deltaP90, int conflictingNeighbours) {
		public static final PolishStats EMPTY = new PolishStats(0, 0, 0, 0);
	}

	/** Mutable per-solve accumulator threaded through the leaf candidate builders. */
	private static final class Stats {
		int candidateContradictions;
		int residualViolations;
		int worstStep;
		int waterAnchorDeviations;
		int phase3Refusals;
		int polishMoved;
		int polishMaxDelta;
		int polishDeltaP90;
		int polishConflicts;

		SolveStats toStats() {
			return new SolveStats(candidateContradictions, residualViolations, worstStep, waterAnchorDeviations,
					phase3Refusals, new PolishStats(polishMoved, polishMaxDelta, polishDeltaP90, polishConflicts));
		}
	}

	private RoadSmoother() {
	}

	public static SolveStats solve(RoadNetwork net, SolveParams p) {
		for (RoadCell cell : net.cells) {
			cell.solvedP = cell.baseP;
		}
		// plan C (user decision 2026-10-15): plaza cells are hard-pinned to their
		// group's level. They act as pre-solved boundary references - exactly the
		// role region cells play for the arms - so they are removed from the
		// region membership below and the interpolation reads their pin as an
		// outside reference. The seam to the roads is the normal |dP| <= 1 edge
		// constraint, which the polish pass makes true by construction.
		BitSet pinnedCells = new BitSet(net.cells.size());
		for (int i = 0; i < net.cells.size(); i++) {
			RoadCell cell = net.cells.get(i);
			if (cell.plazaPinned) {
				pinnedCells.set(i);
				cell.solvedP = cell.plazaPinP;
			}
		}
		List<int[]> regions = new ArrayList<>(net.regions.size());
		for (int[] region : net.regions) {
			List<Integer> kept = new ArrayList<>(region.length);
			for (int cell : region) {
				if (!pinnedCells.get(cell)) {
					kept.add(cell);
				}
			}
			if (!kept.isEmpty()) {
				regions.add(toArray(kept));
			}
		}
		BitSet regionCells = new BitSet(net.cells.size());
		for (int[] region : regions) {
			for (int cell : region) {
				regionCells.set(cell);
			}
		}
		Stats stats = new Stats();

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
		for (int[] region : regions) {
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
				// fix plan v4 A2: the refusal used to be dropped on the floor, so an
				// arm whose band could not fit the state budget silently kept its
				// phase-1 solution - one that does not know the region exists - and
				// the seam cliff that follows was then attributed to the terrain.
				// Refused arms now go through the ICM with the region's heights as
				// boundary references, which costs quality but keeps the constraint.
				if (!solveArm(net, arm, p, seamBoundsForArm(net, arm, regionCells), stats)) {
					stats.phase3Refusals++;
					solveRegion(net, armCells(arm), p, icmMembership, stats);
				}
			}
		}

		// 4. fix plan v4 A-1: the polish pass. Everything above optimises; this
		//    makes the result LEGAL by construction, one cell at a time, walking
		//    outwards from a seed per component. It is also where the plan's A
		//    decision lives: the solver keeps its six defects and this pass
		//    absorbs them, which is only sound because decision N3-a lets the
		//    polish move a cell as far as legality requires (up to 5-8 whole
		//    blocks in the measured worst case) instead of freezing the outliers.
		PolishStats polish = polish(net, p.allowCut(), pinnedCells);

		// global final verification (review 3 P0-A item 3): walk every 4-adjacency
		// edge of the finished network so no violation can stay invisible
		for (int i = 0; i < net.cells.size(); i++) {
			if (pinnedCells.get(i)) {
				continue; // pinned plaza cells are pre-solved; the polish never moves them
			}
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
		// fix plan v2 R4.3: the soft water anchor's counter - cells that left
		// the water surface for a smoother profile
		for (RoadCell cell : net.cells) {
			if (cell.anchor && !cell.plazaPinned && cell.solvedP != cell.baseP) {
				stats.waterAnchorDeviations++;
			}
		}
		stats.polishMoved = polish.movedCells();
		stats.polishMaxDelta = polish.maxDelta();
		stats.polishDeltaP90 = polish.deltaP90();
		stats.polishConflicts = polish.conflictingNeighbours();
		return stats.toStats();
	}

	// ------------------------------------------------------------------
	// polish pass (fix plan v4 A-1, decision N3-a)
	// ------------------------------------------------------------------

	/**
	 * Makes the profile legal by construction, walking outwards from one seed per
	 * 4-connected component.
	 *
	 * <p>Why it is needed at all: the block pipeline optimises arms by exact DP and
	 * junctions by ICM, and six structural defects leave residual edges with
	 * |dP| > 1 (v3 section 3). Legality itself is trivial - a constant profile is
	 * legal on any graph - so that residual is a property of the pipeline, not of
	 * the terrain, and one post-pass removes all of them.
	 *
	 * <p>Construction: fix the seed, then BFS. When a cell is reached, every edge
	 * to an already-fixed neighbour is satisfied by clamping the cell into that
	 * neighbour's [v-1, v+1]; each edge therefore has exactly one later endpoint,
	 * which is legal by construction.
	 *
	 * <p>One case escapes that argument: a cell whose two already-fixed neighbours
	 * disagree by 2 or more has <em>no</em> legal value (lo > hi), because both
	 * ends of those edges are frozen by then. Those cells are counted
	 * ({@link PolishStats#conflictingNeighbours()}) and handed to a projection
	 * sweep, the textbook repair for difference constraints: the feasible set
	 * {|Pu - Pv| <= 1} is convex (an intersection of slabs), cyclic projection
	 * onto a violated slab converges, and projecting both endpoints half the
	 * excess makes that edge legal within the sweep on the integer lattice.
	 *
	 * <p>Why the seed is the component's centre: v3 measured 234 violating edges
	 * when starting from a network end against 0 from the centre, a 28x
	 * difference. An end seed sits on vanilla terrain, so the error pours inwards
	 * from there.
	 *
	 * <p>Decision N3-a: the result is adopted unconditionally, so one cell may be
	 * dragged several whole blocks. That is what "no drop larger than half a block"
	 * costs, and it is counted rather than hidden - the alternative (freeze the
	 * outliers) would leave a known cliff and invalidate the very metric this pass
	 * exists to make zero.
	 *
	 * <p>One exception, and it is the user's own setting: with
	 * {@code allowCutIntoTerrain = false} the road may never sit below its terrain,
	 * so {@code baseP} is a hard floor here too. A terrain step that no legal
	 * no-cut profile can bridge therefore keeps its residual step, counted by the
	 * solve's verification - that is precisely what the flag promises, and
	 * silently legalising it would be the "quietly break the no-cut promise" the
	 * plan forbids.
	 */
	private static int[] toArray(List<Integer> values) {
		int[] out = new int[values.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = values.get(i);
		}
		return out;
	}

	private static PolishStats polish(RoadNetwork net, boolean allowCut, BitSet pinnedCells) {
		int n = net.cells.size();
		if (n == 0) {
			return PolishStats.EMPTY;
		}
		boolean[] fixed = new boolean[n];
		int[] queue = new int[n];
		List<Integer> movedDeltas = new ArrayList<>();
		int moved = 0;
		int maxDelta = 0;
		int conflicts = 0;

		// plan C: pinned plaza cells start FIXED at their pin, so the walk clamps
		// every road cell into the plaza's [pin - 1, pin + 1] when it reaches it
		for (int i = pinnedCells.nextSetBit(0); i >= 0; i = pinnedCells.nextSetBit(i + 1)) {
			fixed[i] = true;
		}
		for (int start = 0; start < n; start++) {
			if (fixed[start]) {
				continue;
			}
			int seed = componentSeed(net, start);
			int head = 0;
			int tail = 0;
			queue[tail++] = seed;
			fixed[seed] = true;
			while (head < tail) {
				int current = queue[head++];
				for (int dir = 0; dir < 4; dir++) {
					int j = net.neighbors[current][dir];
					if (j < 0 || fixed[j]) {
						continue;
					}
					// clamp into the interval of this cell's already-fixed neighbours
					int lo = Integer.MIN_VALUE;
					int hi = Integer.MAX_VALUE;
					for (int other = 0; other < 4; other++) {
						int k = net.neighbors[j][other];
						if (k >= 0 && fixed[k]) {
							int value = net.cells.get(k).solvedP;
							lo = Math.max(lo, value - 1);
							hi = Math.min(hi, value + 1);
						}
					}
					RoadCell target = net.cells.get(j);
					int before = target.solvedP;
					// fix plan v4 D7: with allowCutIntoTerrain = false the road may
					// never sit below the terrain, so the cell's own baseP is a hard
					// floor the polish cannot push through either. plan E: the same
					// floor holds for water cells at every config - the water surface
					// is solid ground; a seat is exempt (it rides the solve and is
					// written there, plan 2026-10-16).
					int floor = (target.anchor || (!allowCut && !target.seat)) ? target.baseP : Integer.MIN_VALUE;
					lo = Math.max(lo, floor);
					int after;
					if (lo > hi) {
						// two fixed neighbours genuinely disagree: no legal value
						// exists here. Counted, and resolved by the sweep below.
						conflicts++;
						after = Math.max(floor, (lo + hi + 1) / 2);
						if (target.seat) {
							after &= ~1; // full-block seat: even walk surfaces only
						}
					} else {
						after = Math.max(lo, Math.min(hi, before));
						if (target.seat && (after & 1) != 0) {
							// snap to the nearest even walk surface inside the clamp
							if (after - 1 >= lo) {
								after -= 1;
							} else {
								after += 1; // after == lo: the even above is inside hi
							}
						}
					}
					target.solvedP = after;
					if (after != before) {
						moved++;
						int delta = Math.abs(after - before);
						maxDelta = Math.max(maxDelta, delta);
						movedDeltas.add(delta);
					}
					fixed[j] = true;
					queue[tail++] = j;
				}
			}
		}
		// conflicts are rare; the sweep is a safety net, not the main mechanism.
		// Seats are immovable here too: the sweep writes arbitrary values and
		// would break their even-parity constraint (plan 2026-10-16).
		if (conflicts > 0) {
			BitSet sweepFixed = (BitSet) pinnedCells.clone();
			for (int i = 0; i < net.cells.size(); i++) {
				if (net.cells.get(i).seat) {
					sweepFixed.set(i);
				}
			}
			for (int sweep = 0; sweep < PROJECTION_SWEEPS; sweep++) {
				if (!projectViolatedEdges(net, movedDeltas, allowCut, sweepFixed)) {
					break;
				}
			}
		}
		return new PolishStats(moved, maxDelta, percentile90(movedDeltas), conflicts);
	}

	/**
	 * One cyclic projection sweep: every edge still too steep has its two endpoints
	 * moved towards each other by half the excess (rounded up, so the edge is legal
	 * once the sweep ends). Returns true when something moved, i.e. when another
	 * sweep is worthwhile.
	 *
	 * <p>With {@code allowCut = false} a cell cannot go below its own {@code baseP},
	 * so the correction it cannot take is transferred to its neighbour. A terrain
	 * step that no legal no-cut profile can bridge therefore keeps its residual -
	 * which is what that flag asks for, and the solve's own verification counts it.
	 *
	 * <p>Pinned cells (plaza groups, fixed base seats) never move here. The sweep
	 * used to shift them like any other cell, which broke the pin guarantee
	 * whenever a polish conflict reached the safety net; now a pinned endpoint is
	 * immovable and the free endpoint takes the whole excess (or the residual
	 * stays, when both endpoints are pinned).
	 */
	private static boolean projectViolatedEdges(RoadNetwork net, List<Integer> movedDeltas, boolean allowCut,
			BitSet pinnedCells) {
		boolean changed = false;
		for (int i = 0; i < net.cells.size(); i++) {
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j <= i) {
					continue;
				}
				boolean pinnedA = pinnedCells.get(i);
				boolean pinnedB = pinnedCells.get(j);
				if (pinnedA && pinnedB) {
					continue; // both endpoints fixed: the residual stays, nothing can move
				}
				RoadCell a = net.cells.get(i);
				RoadCell b = net.cells.get(j);
				int diff = a.solvedP - b.solvedP;
				int excess = Math.abs(diff) - 1;
				if (excess <= 0) {
					continue;
				}
				RoadCell high = diff > 0 ? a : b;
				RoadCell low = diff > 0 ? b : a;
				boolean pinnedHigh = diff > 0 ? pinnedA : pinnedB;
				boolean pinnedLow = diff > 0 ? pinnedB : pinnedA;
				int beforeHigh = high.solvedP;
				int beforeLow = low.solvedP;
				if (pinnedHigh) {
					// the pinned high cannot come down: the free low takes the whole excess up
					low.solvedP += excess;
				} else if (pinnedLow) {
					// the pinned low cannot go up: the high gives what its own floor allows
					int room = (allowCut && !high.anchor && !high.seat) ? excess : Math.max(0, Math.min(excess, high.solvedP - high.baseP));
					high.solvedP -= room;
				} else {
					int half = (excess + 1) / 2;
					int room = (allowCut && !high.anchor && !high.seat) ? half : Math.min(half, high.solvedP - high.baseP);
					if (room < 0) {
						room = 0;
					}
					high.solvedP -= room;
					low.solvedP += half - room;
				}
				if (high.solvedP != beforeHigh || low.solvedP != beforeLow) {
					changed = true;
					movedDeltas.add(Math.abs(high.solvedP - beforeHigh));
					movedDeltas.add(Math.abs(low.solvedP - beforeLow));
				}
			}
		}
		return changed;
	}

	/** Seed of the component containing {@code start}: its cell closest to its own box centre. */
	private static int componentSeed(RoadNetwork net, int start) {
		Deque<Integer> stack = new ArrayDeque<>();
		List<Integer> component = new ArrayList<>();
		stack.push(start);
		boolean[] seen = new boolean[net.cells.size()];
		seen[start] = true;
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		while (!stack.isEmpty()) {
			int i = stack.pop();
			component.add(i);
			RoadCell c = net.cells.get(i);
			minX = Math.min(minX, c.x);
			maxX = Math.max(maxX, c.x);
			minZ = Math.min(minZ, c.z);
			maxZ = Math.max(maxZ, c.z);
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[i][dir];
				if (j >= 0 && !seen[j]) {
					seen[j] = true;
					stack.push(j);
				}
			}
		}
		int centreX = minX + (maxX - minX) / 2;
		int centreZ = minZ + (maxZ - minZ) / 2;
		int best = component.get(0);
		int bestDistance = Integer.MAX_VALUE;
		for (int i : component) {
			RoadCell c = net.cells.get(i);
			int distance = Math.max(Math.abs(c.x - centreX), Math.abs(c.z - centreZ));
			if (distance < bestDistance || (distance == bestDistance && i < best)) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	private static int percentile90(List<Integer> values) {
		if (values.isEmpty()) {
			return 0;
		}
		int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
		return sorted[Math.min(sorted.length - 1, (int) Math.ceil(0.9 * sorted.length) - 1)];
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
	 * Seam geometry of one arm in phase 3, used to extend the candidate bands
	 * toward the region side (review 3 P0-A item 1). {@code hardLo}/{@code hardHi}
	 * hull all seam cells' hard bounds; {@code sliceDistance[s]} is the slice
	 * distance to the nearest slice that contains a seam cell, so a cell d slices
	 * in can still be reached by a legal 1-half-unit-per-slice ramp from the seam.
	 */
	private static final class ArmSeam {
		final int hardLo;
		final int hardHi;
		final int[] sliceDistance;

		ArmSeam(int hardLo, int hardHi, int[] sliceDistance) {
			this.hardLo = hardLo;
			this.hardHi = hardHi;
			this.sliceDistance = sliceDistance;
		}
	}

	private static ArmSeam analyzeSeam(RoadNetwork.Arm arm, int[][] hardBounds) {
		int hardLo = Integer.MAX_VALUE;
		int hardHi = Integer.MIN_VALUE;
		int[] distance = new int[arm.slices.size()];
		Arrays.fill(distance, Integer.MAX_VALUE);
		for (int s = 0; s < arm.slices.size(); s++) {
			boolean seamSlice = false;
			for (int cellIdx : arm.slices.get(s)) {
				if (hardBounds[cellIdx] != null) {
					seamSlice = true;
					hardLo = Math.min(hardLo, hardBounds[cellIdx][0]);
					hardHi = Math.max(hardHi, hardBounds[cellIdx][1]);
				}
			}
			if (seamSlice) {
				distance[s] = 0;
			}
		}
		if (hardLo == Integer.MAX_VALUE) {
			return null;
		}
		for (int s = 1; s < distance.length; s++) {
			distance[s] = Math.min(distance[s], distance[s - 1] == Integer.MAX_VALUE ? Integer.MAX_VALUE : distance[s - 1] + 1);
		}
		for (int s = distance.length - 2; s >= 0; s--) {
			distance[s] = Math.min(distance[s], distance[s + 1] == Integer.MAX_VALUE ? Integer.MAX_VALUE : distance[s + 1] + 1);
		}
		return new ArmSeam(hardLo, hardHi, distance);
	}

	/**
	 * Fills the per-cell candidate bands: the terrain chase hull with the given
	 * margin, then - in phase 3 - the seam extension of review 3 P0-A.
	 *
	 * <p>Seam cells (own hard bound present) get their bound merged into the
	 * band outright, so a seam cell always has legal candidates and the
	 * {@code lo > hi} contradiction that used to silently keep the first-pass
	 * solution can only survive when two region neighbours genuinely disagree.
	 * Interior cells get the legal-slope cone from the nearest seam slice:
	 * when the hard bound sits entirely below (above) the chase band, the band
	 * grows downward (upward) by at most one half unit per slice of distance -
	 * exactly what a maximal legal ramp needs and no more, so the state budget
	 * is spent on the bridge instead of on values the deviation cost would
	 * never pick. When the hard interval overlaps the band on both sides the
	 * full interval is merged (bounded, corner-case shapes).
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
		int chaseLoMin = Integer.MAX_VALUE;
		int chaseHiMax = Integer.MIN_VALUE;
		for (int s = 0; s < arm.slices.size(); s++) {
			for (int cellIdx : arm.slices.get(s)) {
				RoadCell cell = net.cells.get(cellIdx);
				int lo = Math.min(chaseFwd[s], Math.min(chaseBack[s], cell.baseP)) - margin;
				int hi = Math.max(chaseFwd[s], Math.max(chaseBack[s], cell.baseP)) + margin;
				bandLo[cellIdx] = lo;
				bandHi[cellIdx] = hi;
				hasBand[cellIdx] = true;
				chaseLoMin = Math.min(chaseLoMin, lo);
				chaseHiMax = Math.max(chaseHiMax, hi);
			}
		}
		if (seam != null) {
			for (int s = 0; s < arm.slices.size(); s++) {
				int d = seam.sliceDistance[s];
				for (int cellIdx : arm.slices.get(s)) {
					int lo = bandLo[cellIdx];
					int hi = bandHi[cellIdx];
					int[] own = hardBounds[cellIdx];
					if (own != null) {
						// the seam cell itself: merge its own bound so its
						// candidates always intersect the hard bound
						lo = Math.min(lo, own[0]);
						hi = Math.max(hi, own[1]);
					} else if (seam.hardHi < chaseLoMin) {
						// region below the band: legal ramp descends toward it
						lo = Math.min(lo, seam.hardHi + d);
					} else if (seam.hardLo > chaseHiMax) {
						// region above the band: legal ramp climbs toward it
						hi = Math.max(hi, seam.hardLo - d);
					} else {
						// hard interval straddles the band: merge it wholesale
						lo = Math.min(lo, seam.hardLo);
						hi = Math.max(hi, seam.hardHi);
					}
					bandLo[cellIdx] = lo;
					bandHi[cellIdx] = hi;
					maxBand = Math.max(maxBand, hi - lo + 1);
				}
			}
		} else {
			for (int s = 0; s < arm.slices.size(); s++) {
				for (int cellIdx : arm.slices.get(s)) {
					maxBand = Math.max(maxBand, bandHi[cellIdx] - bandLo[cellIdx] + 1);
				}
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

		// Chase lines: the legal profile tracking the terrain as closely as legal
		// steps allow, from the left (chaseFwd) and from the right (chaseBack).
		// For the deviation objectives the optimum lies pointwise between them,
		// so a per-cell candidate band around [min, max] of the two lines and P0
		// contains it while staying small on sustained slopes, where a fixed
		// window around P0 would run out of room.
		int[] chaseFwd = new int[sliceCount];
		int[] chaseBack = new int[sliceCount];
		int[] sliceRef = new int[sliceCount];
		for (int s = 0; s < sliceCount; s++) {
			sliceRef[s] = sliceReference(net, slices.get(s));
		}
		for (int s = 0; s < sliceCount; s++) {
			chaseFwd[s] = s == 0 ? sliceRef[0] : clampToStep(sliceRef[s], chaseFwd[s - 1]);
		}
		for (int s = sliceCount - 1; s >= 0; s--) {
			chaseBack[s] = s == sliceCount - 1 ? sliceRef[s] : clampToStep(sliceRef[s], chaseBack[s + 1]);
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

	/** Terrain height of one slice for the chase lines: the median base height. */
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
		// plan C: a pinned plaza cell has exactly one candidate - its pin. Checked
		// first so no band, no-cut floor or seam bound can empty the candidate set
		if (cell.plazaPinned) {
			return new int[]{cell.plazaPinP};
		}
		// fix plan v2 R4: water anchors are soft. The cell keeps its normal
		// candidate band (which contains the water surface, its baseP), and the
		// cost model decides whether leaving the water pays for itself
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
		// plan E (user decision 2026-10-15): a water cell counts as SOLID - the
		// road surface is determined with the water as the ground, so its baseP
		// (the water surface) is a hard floor regardless of allowCutIntoTerrain.
		// The writer never hoists a soaked road any more because the solve no
		// longer produces one. A seat is exempt from its own floor: it RIDES the
		// solved profile and is WRITTEN there (plan 2026-10-16), not cut into
		// terrain.
		if ((!p.allowCut() && !cell.seat) || cell.anchor) {
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
		if (cell.seat) {
			// a seat renders a FULL block: even walk surfaces only, so the RIGID
			// decor piece on top can shift by whole blocks (plan 2026-10-16)
			int first = (lo & 1) == 0 ? lo : lo + 1;
			if (first > hi) {
				stats.candidateContradictions++;
				return new int[]{cell.solvedP};
			}
			int[] evens = new int[(hi - first) / 2 + 1];
			for (int v = first, k = 0; v <= hi; v += 2, k++) {
				evens[k] = v;
			}
			return evens;
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
			// fix plan v2 R4: water anchors are soft - they are seeded like every
			// other cell; their baseP (the water surface) stays their deviation
			// target in the cost model
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
			// the seed is legalised below before it is written into solvedP:
			// anchoring on an ILLEGAL seed would bake in a step the ICM can no
			// longer climb away from
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
				init[i] = platform;
				filled[i] = true;
			}
		}
		for (int pass = 0; pass < region.length; pass++) {
			boolean changed = false;
			for (int i = 0; i < region.length; i++) {
				if (filled[i]) {
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
	 * Turns the region seed into a legal staircase before it is written into
	 * solvedP (review 4; kept as-is, fix plan v2 A6).
	 *
	 * <p>The seed averages a row interpolation with a column interpolation, so
	 * on a crossing with arms on opposite sides it can step by more than one
	 * half unit between adjacent cells - averaging two legal staircases that
	 * disagree is not itself legal. An illegal seed would bake a step into
	 * solvedP that the ICM then has to climb away from (or hold, with a counted
	 * violation); legalising first guarantees the field the ICM is handed can
	 * be walked.
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
	 * own terrain ({@code baseP}), intersected with the hard bounds from the
	 * fixed orthogonal neighbours.
	 */
	private static int[] runCandidates(RoadNetwork net, RoadCell cell, boolean rowsAlongX, SolveParams p, Stats stats) {
		// plan C: a pinned plaza cell keeps its pin (see candidatesFor)
		if (cell.plazaPinned) {
			return new int[]{cell.plazaPinP};
		}
		int window = Math.max(1, p.windowHalf());
		int centre = cell.baseP;
		// plan E: a water cell's baseP (the water surface) is a hard floor - see
		// candidatesFor; a seat is exempt from its own floor (it rides the solve)
		int lo = ((!p.allowCut() && !cell.seat) || cell.anchor) ? Math.max(centre, cell.baseP) : centre - window;
		if (cell.seat && (lo & 1) != 0) {
			lo++; // full-block seat: even walk surfaces only
		}
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
		if (cell.seat) {
			// full-block seat: even walk surfaces only (see candidatesFor)
			int first = (lo & 1) == 0 ? lo : lo + 1;
			if (first > hi) {
				stats.candidateContradictions++;
				return new int[]{cell.solvedP};
			}
			int[] evens = new int[(hi - first) / 2 + 1];
			for (int v = first, k = 0; v <= hi; v += 2, k++) {
				evens[k] = v;
			}
			return evens;
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
	 * and per-edge terms:
	 * <ul>
	 *   <li>SMOOTH_FIRST (default): primary = penalty*sum(excess), secondary = sum|dP|, tertiary = sum|P-P0|</li>
	 *   <li>MIN_DEVIATION: primary = sum|P-P0| + penalty*sum(excess), secondary = sum|dP|</li>
	 *   <li>SHORTEST_CATCHUP: primary = deviating cells, secondary = sum|P-P0| + penalty*sum(excess), tertiary = sum|dP|</li>
	 *   <li>HYBRID: primary = w1*sum|P-P0| + penalty*sum(excess), secondary = w2*sum|dP|</li>
	 * </ul>
	 * SMOOTH_FIRST keeps the three ranks strictly separated (cliff penalty above
	 * total step count above total deviation), which is what P1 "roads smooth
	 * first" means; the legacy MIN_DEVIATION adds deviation and steps in the
	 * same rank. Deviation is always measured against raw terrain, the cell's
	 * {@code baseP}.
	 */
	private static final class CostModel {
		/** Deviations beyond this many half units all cost the same (overflow guard, report 2.1/5-�?. */
		private static final int EXCESS_SATURATION = 1 << 12;

		private final Objective objective;
		private final int penalty;
		private final int hybridW1;
		private final int hybridW2 = 16;
		private final long s2;
		private final long s1;
		private final long sDev;
		/** SMOOTH_FIRST scales: sLow = deviation rank, sMid = step rank, sTop = cliff penalty rank. */
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
			// Report 2.1/5-�? the lexicographic products (penalty|hybridW1) *
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
			// sLow: deviation rank (1 unit); sMid: step rank (> max total dev);
			// sTop: cliff penalty rank (> max total step). Clamped to room so the
			// component worst case stays under Long.MAX_VALUE/8; on huge
			// components the ranks blur gracefully instead of wrapping.
			long sLowV = 1;
			long sMidV = satAdd(satMul(Math.max(1, cellCount), EXCESS_SATURATION), 1);
			long sTopV = satAdd(satDiv(satMul(Math.max(1, edgeCount), satMul(EXCESS_SATURATION, sMidV)), Math.max(1, this.penalty)), 1);
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
			int dev = Math.min(Math.abs(value - cell.baseP), EXCESS_SATURATION);
			switch (objective) {
				case MIN_DEVIATION:
					return dev * s2;
				case SHORTEST_CATCHUP:
					return (dev != 0 ? 1L : 0L) * s1 * sDev * s2 + dev * s2;
				case SMOOTH_FIRST:
					return dev * sLow;
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
					// strict ranks: cliff penalty on top, total steps in the
					// middle, total deviation at the bottom
					return (long) penalty * excess * sTop + (long) delta * sMid;
				default:
					return (long) penalty * excess * s2 + (long) hybridW2 * delta;
			}
		}
	}
}

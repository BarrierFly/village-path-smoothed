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
 * solved by iterative coordinate minimization (row/column runs re-optimized
 * with hard bounds from fixed orthogonal neighbours) followed by a hard pass
 * that guarantees every 4-adjacency constraint is exactly satisfied.
 */
public final class RoadSmoother {
	/** Upper bound on enumerated states per slice before the DP window shrinks. */
	private static final int STATE_BUDGET = 30_000;
	/** Hard cap on the per-cell candidate half-window; larger fills/cuts never beat the step penalty anyway. */
	private static final int WINDOW_HARD_CAP = 8;

	public record SolveParams(
			Objective objective,
			int hybridWeightScaled,
			int stepViolationPenalty,
			int windowHalf,
			int icmRounds,
			boolean allowCut
	) {
	}

	private RoadSmoother() {
	}

	public static void solve(RoadNetwork net, SolveParams p) {
		for (RoadCell cell : net.cells) {
			cell.solvedP = cell.baseP;
		}
		BitSet regionCells = new BitSet(net.cells.size());
		for (int[] region : net.regions) {
			for (int cell : region) {
				regionCells.set(cell);
			}
		}

		// 1. arms with free boundaries; arms whose chase band is too wide for the
		//    exact DP fall back to the region solver (constraints stay exact)
		List<int[]> icmFallback = new ArrayList<>();
		for (RoadNetwork.Arm arm : net.arms) {
			if (!solveArm(net, arm, p, regionCells, null)) {
				icmFallback.add(armCells(arm));
			}
		}
		// 2. regions (platform init from arm boundaries + ICM)
		for (int[] region : net.regions) {
			solveRegion(net, region, p, regionCells);
		}
		for (int[] fallback : icmFallback) {
			solveRegion(net, fallback, p, regionCells);
		}
		// 3. arms glued to a region: re-solve with the seam clipped to the fixed
		//    region heights, making the arm/region transition exact
		for (RoadNetwork.Arm arm : net.arms) {
			if (arm.touchesRegion) {
				solveArm(net, arm, p, regionCells, seamBoundsForArm(net, arm, regionCells));
			}
		}
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

	private static boolean solveArm(RoadNetwork net, RoadNetwork.Arm arm, SolveParams p, BitSet regionCells, int[][] hardBounds) {
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
		int margin = Math.max(0, Math.min(p.windowHalf(), 4));
		int maxBand = 0;
		int[] bandLo = new int[net.cells.size()];
		int[] bandHi = new int[net.cells.size()];
		boolean[] hasBand = new boolean[net.cells.size()];
		for (int s = 0; s < sliceCount; s++) {
			for (int cellIdx : slices.get(s)) {
				RoadCell cell = net.cells.get(cellIdx);
				int lo = Math.min(chaseFwd[s], Math.min(chaseBack[s], cell.baseP)) - margin;
				int hi = Math.max(chaseFwd[s], Math.max(chaseBack[s], cell.baseP)) + margin;
				bandLo[cellIdx] = lo;
				bandHi[cellIdx] = hi;
				hasBand[cellIdx] = true;
				maxBand = Math.max(maxBand, hi - lo + 1);
			}
		}
		while (margin > 0 && powOf(maxBand, maxSliceWidth) > STATE_BUDGET) {
			margin--;
			maxBand = 0;
			for (int s = 0; s < sliceCount; s++) {
				for (int cellIdx : slices.get(s)) {
					maxBand = Math.max(maxBand, bandHi[cellIdx] - bandLo[cellIdx] + 1 - 2 * margin);
				}
			}
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
				candSlice[pos] = candidatesFor(cell, window, p, hardBounds, bandLo, bandHi, hasBand);
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

	/** Reference height of one slice for the chase lines: the median base height (anchors carry their fixed value). */
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

			long[] out = new long[(int) sizeOf(outDims)];
			long outSize = sizeOf(outDims);
			for (long outIdx = 0; outIdx < outSize; outIdx++) {
				decodeMixedRadix(outDims, outIdx, coords);
				int tValue = slot >= 0 ? coords[slot] : 0;
				// m's domain does not include the t dim introduced by this step,
				// so its coordinate must read as zero when indexing into m
				if (slot >= 0) {
					coords[slot] = 0;
				}
				long best = Long.MAX_VALUE;
				for (int v = 0; v < dims[pos]; v++) {
					coords[pos] = v;
					long k = kernels[pos] != null ? kernels[pos][v][tValue] : 0L;
					long value = m[(int) encodeMixedRadix(dims, coords)] + k;
					if (value < best) {
						best = value;
					}
				}
				coords[pos] = 0;
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
		int[][] argmins = new int[w][];
		int[][] argminDims = new int[w][];

		for (int pos = 0; pos < w; pos++) {
			int[] outDims = new int[w];
			System.arraycopy(dims, 0, outDims, 0, w);
			outDims[pos] = 1;
			int tPos = aligned[pos];
			int tValue = tPos >= 0 ? tIndices[tPos] : 0;

			long[] out = new long[(int) sizeOf(outDims)];
			argmins[pos] = new int[out.length];
			argminDims[pos] = outDims;
			long outSize = sizeOf(outDims);
			for (long outIdx = 0; outIdx < outSize; outIdx++) {
				decodeMixedRadix(outDims, outIdx, coords);
				long best = Long.MAX_VALUE;
				int bestV = 0;
				for (int v = 0; v < dims[pos]; v++) {
					coords[pos] = v;
					long k = kernels[pos] != null ? kernels[pos][v][tValue] : 0L;
					long value = m[(int) encodeMixedRadix(dims, coords)] + k;
					if (value < best) {
						best = value;
						bestV = v;
					}
				}
				coords[pos] = 0;
				out[(int) outIdx] = best;
				argmins[pos][(int) outIdx] = bestV;
			}
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
		int m = Math.max(1, Math.min(maxWindow, WINDOW_HARD_CAP));
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

	private static int[] candidatesFor(RoadCell cell, int window, SolveParams p, int[][] hardBounds, int[] bandLo, int[] bandHi, boolean[] hasBand) {
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
			// contradictory bounds: keep the current value as the only option
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

	private static void solveRegion(RoadNetwork net, int[] region, SolveParams p, BitSet regionCells) {
		// platform initialisation: median of the boundary (arm side) heights
		int[] boundaryValues = new int[region.length * 4];
		int count = 0;
		for (int cell : region) {
			if (net.cells.get(cell).anchor) {
				continue;
			}
			for (int dir = 0; dir < 4; dir++) {
				int j = net.neighbors[cell][dir];
				if (j >= 0 && !regionCells.get(j)) {
					boundaryValues[count++] = net.cells.get(j).solvedP;
				}
			}
		}
		int platform;
		if (count > 0) {
			platform = median(boundaryValues, count);
		} else {
			int[] base = new int[region.length];
			int c = 0;
			for (int cell : region) {
				base[c++] = net.cells.get(cell).baseP;
			}
			platform = median(base, c);
		}
		for (int cell : region) {
			RoadCell rc = net.cells.get(cell);
			if (!rc.anchor) {
				rc.solvedP = platform;
			}
		}

		// ICM rounds: x runs then z runs with soft transitions
		for (int round = 0; round < Math.max(0, p.icmRounds()); round++) {
			scanRuns(net, region, true, true, p);
			scanRuns(net, region, false, true, p);
		}
		// final hard passes: every 4-adjacency edge ends up exactly satisfied
		scanRuns(net, region, true, false, p);
		scanRuns(net, region, false, false, p);
	}

	/**
	 * Re-optimizes every maximal run of region cells along one axis.
	 * {@code rowsAlongX}: runs share z when true, share x when false.
	 * {@code soft}: |dP| > 1 transitions are allowed at penalty cost; when
	 * false the transitions are hard so the pass cannot introduce violations.
	 */
	private static void scanRuns(RoadNetwork net, int[] region, boolean rowsAlongX, boolean soft, SolveParams p) {
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
			optimizeRun(net, run, rowsAlongX, soft, p);
		}
	}

	private static void optimizeRun(RoadNetwork net, List<Integer> run, boolean rowsAlongX, boolean soft, SolveParams p) {
		int n = run.size();
		if (n == 0) {
			return;
		}
		CostModel cost = new CostModel(p, n, n + 4, Math.min(p.windowHalf(), WINDOW_HARD_CAP));
		int[][] candidates = new int[n][];
		long[][] cellCosts = new long[n][];
		for (int i = 0; i < n; i++) {
			RoadCell cell = net.cells.get(run.get(i));
			candidates[i] = runCandidates(net, cell, rowsAlongX, p);
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

	/** Candidates for one cell inside an ICM run: window intersected with hard bounds from fixed orthogonal neighbours. */
	private static int[] runCandidates(RoadNetwork net, RoadCell cell, boolean rowsAlongX, SolveParams p) {
		if (cell.anchor) {
			return new int[]{cell.baseP};
		}
		int window = Math.min(p.windowHalf(), WINDOW_HARD_CAP);
		int lo = p.allowCut() ? cell.baseP - window : cell.baseP;
		int hi = cell.baseP + window;
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
	 * and per-edge terms:
	 * <ul>
	 *   <li>MIN_DEVIATION: primary = sum|P-P0| + penalty*sum(excess), secondary = sum|dP|</li>
	 *   <li>SHORTEST_CATCHUP: primary = deviating cells, secondary = sum|P-P0| + penalty*sum(excess), tertiary = sum|dP|</li>
	 *   <li>HYBRID: primary = w1*sum|P-P0| + penalty*sum(excess), secondary = w2*sum|dP|</li>
	 * </ul>
	 */
	private static final class CostModel {
		private final Objective objective;
		private final int penalty;
		private final int hybridW1;
		private final int hybridW2 = 16;
		private final long s2;
		private final long s1;
		private final long sDev;

		CostModel(SolveParams p, int cellCount, int edgeCount, int window) {
			this.objective = p.objective();
			this.penalty = Math.max(0, p.stepViolationPenalty());
			this.hybridW1 = Math.max(1, p.hybridWeightScaled());
			long stepScale = objective == Objective.HYBRID ? hybridW2 : 1;
			this.s2 = Math.max(1, edgeCount * 4L * window * stepScale + 1);
			this.sDev = Math.max(1, cellCount * 4L * window + 1);
			// SHORTEST_CATCHUP lexicographic order: (step-violation penalty,
			// deviating cells, sum|P-P0|, sum|dP|) - the penalty sits on top so
			// legal profiles always win and jumps only appear when no legal
			// profile fits the geometry
			this.s1 = Math.max(1, cellCount + 1);
		}

		long cellCost(RoadCell cell, int value) {
			int dev = Math.abs(value - cell.baseP);
			switch (objective) {
				case MIN_DEVIATION:
					return dev * s2;
				case SHORTEST_CATCHUP:
					return (dev != 0 ? 1L : 0L) * s1 * sDev * s2 + dev * s2;
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
			int delta = Math.abs(a - b);
			int excess = Math.max(0, delta - 1);
			switch (objective) {
				case MIN_DEVIATION:
					return (long) penalty * excess * s2 + delta;
				case SHORTEST_CATCHUP:
					// penalty layer must use the same top-layer scale as the
					// deviating-cell term in cellCost
					return (long) penalty * excess * s1 * sDev * s2 + delta;
				default:
					return (long) penalty * excess * s2 + (long) hybridW2 * delta;
			}
		}
	}
}

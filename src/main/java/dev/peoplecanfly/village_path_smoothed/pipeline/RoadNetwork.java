/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The road mask rebuilt from street templates, decomposed into
 * arms (thin strips solved by exact slice DP) and regions (wide areas solved
 * by iterative coordinate minimization). Pure data, no Minecraft imports.
 *
 * Decomposition rule (plan section 5.1.4): a cell belongs to a region core when
 * BOTH its horizontal run and its vertical run are longer than
 * {@code maxExactWidth}; everything else forms arm clusters, which are sliced
 * along their dominant axis. A cluster that is too wide to slice in both axes
 * is solved as a region too.
 */
public final class RoadNetwork {
	public static final int DIR_WEST = 0;
	public static final int DIR_EAST = 1;
	public static final int DIR_NORTH = 2;
	public static final int DIR_SOUTH = 3;
	private static final int[] DIR_DX = {-1, 1, 0, 0};
	private static final int[] DIR_DZ = {0, 0, -1, 1};

	public final List<RoadCell> cells = new ArrayList<>();
	/** 4-neighbour cell indices per cell; missing neighbours are -1. */
	public final int[][] neighbors;
	public final List<Arm> arms = new ArrayList<>();
	/** Region cell index arrays. */
	public final List<int[]> regions = new ArrayList<>();
	/**
	 * Cells that belong to a region core. The region solver owns the seed of
	 * exactly these cells; arm cells - including arms that fall back to the
	 * region solver because their band blew the state budget - keep the phase-1
	 * arm solution, and the ICM re-optimises them from boundary references like
	 * any other cell.
	 */
	public final BitSet regionCells = new BitSet();

	private final Map<Long, Integer> cellIndexLookup;

	private RoadNetwork(Map<Long, Integer> cellIndexLookup, int[][] neighbors) {
		this.cellIndexLookup = cellIndexLookup;
		this.neighbors = neighbors;
	}

	public int cellAt(int x, int z) {
		return cellIndexLookup.getOrDefault(RoadCell.packedXZ(x, z), -1);
	}

	// ------------------------------------------------------------------
	// connectivity (fix plan v4 D1-e)
	// ------------------------------------------------------------------

	/**
	 * Number of 4-connected components of the mask.
	 *
	 * <p>Fix plan v4 D1/D1-e: a dropped probe cell does not just lose one cell,
	 * it splits the network. The two halves then have no edge between them at
	 * all, each solves on its own, and the dropped column keeps a full-height
	 * vanilla road in between - a drop that every |dP| counter is blind to. So
	 * connectivity is a first-class property of the mask, not something the
	 * solver is expected to notice.
	 */
	public int componentCount() {
		boolean[] seen = new boolean[neighbors.length];
		int components = 0;
		for (int start = 0; start < neighbors.length; start++) {
			if (seen[start]) {
				continue;
			}
			components++;
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(start);
			seen[start] = true;
			while (!stack.isEmpty()) {
				int i = stack.pop();
				for (int dir = 0; dir < 4; dir++) {
					int j = neighbors[i][dir];
					if (j >= 0 && !seen[j]) {
						seen[j] = true;
						stack.push(j);
					}
				}
			}
		}
		return components;
	}

	/** True when the mask is one single 4-connected component (or empty). */
	public boolean isConnected() {
		return componentCount() <= 1;
	}

	/** Size of the largest 4-connected component divided by the cell count; 1.0 when empty. */
	public double largestComponentRatio() {
		int n = neighbors.length;
		if (n == 0) {
			return 1.0;
		}
		boolean[] seen = new boolean[n];
		int largest = 0;
		for (int start = 0; start < n; start++) {
			if (seen[start]) {
				continue;
			}
			int size = 0;
			Deque<Integer> stack = new ArrayDeque<>();
			stack.push(start);
			seen[start] = true;
			while (!stack.isEmpty()) {
				int i = stack.pop();
				size++;
				for (int dir = 0; dir < 4; dir++) {
					int j = neighbors[i][dir];
					if (j >= 0 && !seen[j]) {
						seen[j] = true;
						stack.push(j);
					}
				}
			}
			largest = Math.max(largest, size);
		}
		return (double) largest / n;
	}

	public static final class Arm {
		/** Cell indices per slice, slices ordered along the sweep axis. */
		public final List<int[]> slices;
		/** Sweep axis: 0 = slices grouped by z, 1 = slices grouped by x. */
		public final int sweepAxis;
		/** True when the arm touches a region and will be re-solved after the regions. */
		public boolean touchesRegion;

		Arm(List<int[]> slices, int sweepAxis) {
			this.slices = slices;
			this.sweepAxis = sweepAxis;
		}
	}

	public static RoadNetwork build(List<RoadCell> roadCells, int maxExactWidth) {
		roadCells.sort(Comparator.comparingLong((RoadCell c) -> RoadCell.packedXZ(c.x, c.z)));
		Map<Long, Integer> lookup = new HashMap<>();
		for (int i = 0; i < roadCells.size(); i++) {
			RoadCell cell = roadCells.get(i);
			cell.index = i;
			lookup.put(RoadCell.packedXZ(cell.x, cell.z), i);
		}

		int n = roadCells.size();
		int[][] neighbors = new int[n][4];
		for (int i = 0; i < n; i++) {
			RoadCell cell = roadCells.get(i);
			for (int dir = 0; dir < 4; dir++) {
				neighbors[i][dir] = lookup.getOrDefault(RoadCell.packedXZ(cell.x + DIR_DX[dir], cell.z + DIR_DZ[dir]), -1);
			}
		}

		RoadNetwork network = new RoadNetwork(lookup, neighbors);
		network.cells.addAll(roadCells);
		if (n == 0) {
			return network;
		}

		int[] xRun = computeRuns(neighbors, DIR_WEST, DIR_EAST);
		int[] zRun = computeRuns(neighbors, DIR_NORTH, DIR_SOUTH);

		boolean[] core = new boolean[n];
		for (int i = 0; i < n; i++) {
			core[i] = xRun[i] > maxExactWidth && zRun[i] > maxExactWidth;
		}

		// Region cores and arm clusters. Arm clusters that cannot be sliced on
		// either axis are treated as regions. The region membership pass runs
		// after this classification so arms see the final region set.
		boolean[] visitedCore = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (core[i] && !visitedCore[i]) {
				network.regions.add(collectComponent(i, core, visitedCore, neighbors));
			}
		}
		boolean[] nonCore = new boolean[n];
		for (int i = 0; i < n; i++) {
			nonCore[i] = !core[i];
		}
		boolean[] visitedArm = new boolean[n];
		List<int[]> armComponents = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (core[i] || visitedArm[i]) {
				continue;
			}
			armComponents.add(collectComponent(i, nonCore, visitedArm, neighbors));
		}

		List<Arm> pendingArms = new ArrayList<>();
		for (int[] component : armComponents) {
			int[] axisOut = new int[1];
			List<int[]> slices = trySliceComponent(component, roadCells, maxExactWidth, axisOut);
			if (slices != null) {
				pendingArms.add(new Arm(slices, axisOut[0]));
			} else {
				network.regions.add(component);
			}
		}

		boolean[] regionMembership = new boolean[n];
		for (int[] region : network.regions) {
			for (int cell : region) {
				regionMembership[cell] = true;
				network.regionCells.set(cell);
			}
		}
		for (Arm arm : pendingArms) {
			for (int[] slice : arm.slices) {
				for (int cell : slice) {
					for (int dir = 0; dir < 4; dir++) {
						int j = neighbors[cell][dir];
						if (j >= 0 && regionMembership[j]) {
							arm.touchesRegion = true;
						}
					}
				}
			}
			network.arms.add(arm);
		}

		return network;
	}

	/** Run lengths through each cell along one axis. */
	private static int[] computeRuns(int[][] neighbors, int backDir, int fwdDir) {
		int n = neighbors.length;
		int[] runs = new int[n];
		for (int i = 0; i < n; i++) {
			if (runs[i] != 0) {
				continue;
			}
			int back = 0;
			int j = neighbors[i][backDir];
			while (j >= 0) {
				back++;
				j = neighbors[j][backDir];
			}
			int fwd = 0;
			j = neighbors[i][fwdDir];
			while (j >= 0) {
				fwd++;
				j = neighbors[j][fwdDir];
			}
			int length = back + 1 + fwd;
			runs[i] = length;
			j = neighbors[i][backDir];
			while (j >= 0) {
				runs[j] = length;
				j = neighbors[j][backDir];
			}
			j = neighbors[i][fwdDir];
			while (j >= 0) {
				runs[j] = length;
				j = neighbors[j][fwdDir];
			}
		}
		return runs;
	}

	private static int[] collectComponent(int start, boolean[] membership, boolean[] visited, int[][] neighbors) {
		Deque<Integer> stack = new ArrayDeque<>();
		List<Integer> out = new ArrayList<>();
		stack.push(start);
		visited[start] = true;
		while (!stack.isEmpty()) {
			int i = stack.pop();
			out.add(i);
			for (int dir = 0; dir < 4; dir++) {
				int j = neighbors[i][dir];
				if (j >= 0 && !visited[j] && membership[j]) {
					visited[j] = true;
					stack.push(j);
				}
			}
		}
		return toSortedArray(out);
	}

	private static int[] toSortedArray(List<Integer> values) {
		int[] arr = new int[values.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = values.get(i);
		}
		Arrays.sort(arr);
		return arr;
	}

	/**
	 * Groups the component into slices along an axis. Returns null when no axis
	 * produces slices that are all within the exact-DP width limit. The chosen
	 * axis is stored in {@code axisOut[0]} on success.
	 */
	private static List<int[]> trySliceComponent(int[] component, List<RoadCell> cells, int maxExactWidth, int[] axisOut) {
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		for (int cell : component) {
			RoadCell c = cells.get(cell);
			minX = Math.min(minX, c.x);
			maxX = Math.max(maxX, c.x);
			minZ = Math.min(minZ, c.z);
			maxZ = Math.max(maxZ, c.z);
		}
		int firstTry = (maxX - minX) >= (maxZ - minZ) ? 1 : 0;
		for (int attempt = 0; attempt < 2; attempt++) {
			int axis = attempt == 0 ? firstTry : 1 - firstTry;
			List<int[]> slices = sliceAlong(component, cells, axis);
			boolean fits = true;
			for (int[] slice : slices) {
				if (slice.length > maxExactWidth) {
					fits = false;
					break;
				}
			}
			if (fits) {
				axisOut[0] = axis;
				return slices;
			}
		}
		return null;
	}

	/** Groups cells by coordinate along {@code axis} (0 = group by z, 1 = group by x). */
	private static List<int[]> sliceAlong(int[] component, List<RoadCell> cells, int axis) {
		Map<Integer, List<Integer>> byCoord = new HashMap<>();
		for (int cell : component) {
			RoadCell c = cells.get(cell);
			int coord = axis == 0 ? c.z : c.x;
			byCoord.computeIfAbsent(coord, k -> new ArrayList<>()).add(cell);
		}
		List<Integer> coords = new ArrayList<>(byCoord.keySet());
		coords.sort(null);
		List<int[]> slices = new ArrayList<>(coords.size());
		for (int coord : coords) {
			List<Integer> group = byCoord.get(coord);
			group.sort(null);
			slices.add(toSortedArray(group));
		}
		return slices;
	}
}
